package com.phicomm.speaker.device.custom.music;

import com.unisound.vui.util.LogMgr;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌曲宝 (gequbao.com) 音乐 API 客户端
 *
 * Android 5.1 兼容版本，使用 HttpURLConnection，不依赖 Gson / java.util.Base64。
 *
 * 2026-10 重写：对齐 karaoke 项目里已验证可用的新版协议（sources.py / gequbao）。
 * 旧版之所以全线失败，是 gequbao 把取直链的接口换掉了：
 *   旧: POST /api/play-url          传 JSON {"id": play_id}
 *   新: POST /member/common-play-url 传表单，且必须带 purpose=play + X-Requested-With
 * 另外新版返回体以 code 判定成功（code==1），code==2 表示要人机验证。
 *
 * API 流程:
 * 1. GET /s/{keyword}                  -> HTML，解析歌曲 ID
 * 2. GET /music/{id}                   -> HTML，提取 play_id
 * 3. POST /member/common-play-url      -> JSON，取真实播放直链（酷我 CDN）
 */
public class NetEaseMusicClient {
    private static final String TAG = "MusicClient";

    // 默认 API 地址
    private static final String BASE_URL = "https://www.gequbao.com";

    // API 端点
    private static final String SEARCH_PATH = "/s/";
    private static final String MUSIC_PATH = "/music/";
    /** 新版取直链接口（旧版 /api/play-url 已失效） */
    private static final String PLAY_URL_PATH = "/member/common-play-url";

    // 超时设置（Android 5.1 兼容）
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 15000;

    // 网络抖动重试：酷我直链偶发失败，单次失败整首歌就播不了，必须重试
    private static final int MAX_RETRY = 3;

    // 直链缓存 30 分钟（酷我签名有时效，但不能每次都重新解链）
    private static final long URL_CACHE_TTL_MS = 30 * 60 * 1000L;

    // 取直链时最多尝试几首候选（部分歌要人机验证，得跳到下一首）
    private static final int MAX_CANDIDATES = 5;

    // 匹配歌曲链接: <a href="/music/39466" title="园游会 - 周杰伦">
    private static final Pattern MUSIC_LINK_PATTERN = Pattern.compile(
        "href=\"/music/(\\d+)\"[^>]*title=\"([^\"]+)\""
    );

    // 匹配 play_id: 页面HTML中 play_id\u0022:\u0022(base64)\u0022
    // 注意：gequbao.com 页面中字面存储的是 \u0022（6个字符），不是双引号
    // Java字符串中需要4个反斜杠来匹配字面的 \u0022
    private static final Pattern PLAY_ID_PATTERN = Pattern.compile(
        "play_id\\\\u0022:\\\\u0022([A-Za-z0-9+/=]+)\\\\u0022"
    );

    // 返回体里的 code 字段：1=成功，2=需要人机验证
    private static final Pattern CODE_PATTERN = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");

    private static final String UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";

    private String baseUrl;

    /** 直链缓存上限: 超了先清过期, 仍超再按插入顺序淘汰最旧 */
    private static final int URL_CACHE_MAX = 100;
    // songId -> (过期时间戳, 直链); 有界, 7x24 开机不再只增不减
    private final Map<String, CacheEntry> urlCache = new java.util.LinkedHashMap<String, CacheEntry>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > URL_CACHE_MAX;
        }
    };

    public NetEaseMusicClient() {
        this(BASE_URL);
    }

    public NetEaseMusicClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 搜索歌曲（解析 HTML）
     * @param keyword 搜索关键词（歌曲名、歌手名等）
     * @return 歌曲列表
     */
    public List<MusicSearchResult> search(String keyword) {
        List<MusicSearchResult> results = new ArrayList<MusicSearchResult>();

        try {
            String urlStr = baseUrl + SEARCH_PATH + URLEncoder.encode(keyword, "UTF-8");
            LogMgr.d(TAG, "search url: " + urlStr);

            String html = httpGet(urlStr);
            if (html == null || html.isEmpty()) {
                LogMgr.e(TAG, "search failed: empty response");
                return results;
            }

            Matcher matcher = MUSIC_LINK_PATTERN.matcher(html);
            // 同一首歌在页面里连排占两个链接（id 两两相邻重复），不去重会占两行
            List<String> seenIds = new ArrayList<String>();
            while (matcher.find()) {
                String id = matcher.group(1);
                String title = matcher.group(2);
                if (seenIds.contains(id)) {
                    continue;
                }
                seenIds.add(id);

                MusicSearchResult item = new MusicSearchResult();
                item.id = id;

                // 标题来自 HTML，实测出现过 "M&amp;Y" 这种未解码实体
                title = htmlUnescape(title);
                // 只按第一个 " - " 切，歌名里本身带 "-" 的情况不能被切碎
                int sep = title.indexOf(" - ");
                if (sep >= 0) {
                    item.name = title.substring(0, sep).trim();
                    item.artist = title.substring(sep + 3).trim();
                } else {
                    item.name = title.trim();
                    item.artist = "";
                }

                results.add(item);
                LogMgr.d(TAG, "found: " + item.name + " - " + item.artist + " (id=" + item.id + ")");

                // 限制最多返回 10 首
                if (results.size() >= 10) break;
            }

        } catch (Exception e) {
            LogMgr.e(TAG, "search error: " + e);
        }

        return results;
    }

    /**
     * 获取歌曲播放 URL
     * @param songId 歌曲 ID
     * @return 播放 URL，如果获取失败返回 null
     */
    public String getPlayUrl(String songId) {
        PlayUrlResult r = resolvePlayUrl(songId);
        return (r != null) ? r.url : null;
    }

    /**
     * 取直链，带状态（区分「失败」和「要人机验证」）。
     * 音箱没有屏幕，没法让人输验证码，所以遇到 code==2 要跳到下一首候选。
     */
    public PlayUrlResult resolvePlayUrl(String songId) {
        // 命中缓存直接返回
        synchronized (urlCache) {
            CacheEntry hit = urlCache.get(songId);
            if (hit != null && hit.expireAt > System.currentTimeMillis()) {
                LogMgr.d(TAG, "play url cache hit: " + songId);
                PlayUrlResult cached = new PlayUrlResult();
                cached.url = hit.url;
                cached.captchaRequired = false;
                return cached;
            }
        }

        try {
            // 第1步：访问 /music/{id} 页面获取 play_id
            String musicUrl = baseUrl + MUSIC_PATH + songId;
            LogMgr.d(TAG, "getPlayUrl music page: " + musicUrl);

            String musicHtml = httpGet(musicUrl);
            if (musicHtml == null || musicHtml.isEmpty()) {
                LogMgr.e(TAG, "getPlayUrl failed: empty music page");
                return null;
            }

            Matcher playIdMatcher = PLAY_ID_PATTERN.matcher(musicHtml);
            if (!playIdMatcher.find()) {
                LogMgr.e(TAG, "getPlayUrl failed: no play_id found");
                return null;
            }
            String playId = playIdMatcher.group(1);
            LogMgr.d(TAG, "play_id: " + playId);

            // 第2步：POST /member/common-play-url 取直链
            // 接口改版频繁，按时间倒序试规则：新规则优先，旧规则兜底。
            // 规则1（2026-09-14 起必填）：id + purpose=play
            // 规则2（旧）：只要 id
            String[][] rules = new String[][]{
                new String[]{"purpose", "play"},
                new String[0]
            };

            String apiUrl = baseUrl + PLAY_URL_PATH;
            for (String[] rule : rules) {
                StringBuilder form = new StringBuilder();
                form.append("id=").append(urlEncode(playId));
                for (int i = 0; i + 1 < rule.length; i += 2) {
                    form.append("&").append(urlEncode(rule[i]))
                        .append("=").append(urlEncode(rule[i + 1]));
                }

                // 4xx 是「请求体形状不对」，重试没意义，所以每条规则只打一次
                String json = httpPostForm(apiUrl, form.toString(), musicUrl, 1);
                if (json == null || json.isEmpty()) {
                    LogMgr.d(TAG, "play-url rule failed (empty), next rule");
                    continue;
                }

                Matcher codeMatcher = CODE_PATTERN.matcher(json);
                if (!codeMatcher.find()) {
                    LogMgr.e(TAG, "play-url: no code in response: " + json);
                    continue;
                }
                int code = Integer.parseInt(codeMatcher.group(1));

                if (code == 2) {
                    // 多为版权敏感的歌曲，强制人机验证。音箱无屏，交给上层换一首
                    LogMgr.d(TAG, "play-url: captcha required for " + songId);
                    PlayUrlResult cr = new PlayUrlResult();
                    cr.url = null;
                    cr.captchaRequired = true;
                    return cr;
                }
                if (code != 1) {
                    LogMgr.d(TAG, "play-url: code=" + code + ", next rule");
                    continue;
                }

                String playUrl = extractJsonUrl(json);
                if (playUrl == null) {
                    LogMgr.e(TAG, "play-url: code=1 but no url");
                    continue;
                }

                synchronized (urlCache) {
                    if (urlCache.size() >= URL_CACHE_MAX) {
                        long nowTs = System.currentTimeMillis();
                        java.util.Iterator<CacheEntry> it = urlCache.values().iterator();
                        while (it.hasNext()) if (it.next().expireAt <= nowTs) it.remove();
                    }
                    urlCache.put(songId,
                        new CacheEntry(playUrl, System.currentTimeMillis() + URL_CACHE_TTL_MS));
                }

                PlayUrlResult ok = new PlayUrlResult();
                ok.url = playUrl;
                ok.captchaRequired = false;
                return ok;
            }

            LogMgr.e(TAG, "getPlayUrl failed: all rules exhausted");
            return null;

        } catch (Exception e) {
            LogMgr.e(TAG, "getPlayUrl error: " + e);
            return null;
        }
    }

    /**
     * 搜索并获取第一首「能播」的歌曲。
     *
     * 老实现只试第一首，第一首要是撞上人机验证就直接播不了。
     * 这里按顺序试前 MAX_CANDIDATES 首，跳过要验证码的，取第一个拿到直链的。
     *
     * @param keyword 搜索关键词
     * @return 包含 (歌曲名, 歌手, 播放URL) 的结果，失败返回 null
     */
    public MusicPlayResult searchAndGetFirst(String keyword) {
        // 2026-10: gequbao 上了 Cloudflare 人机验证, 设备端一律 403。
        // 先走酷我官方接口(纯 http, 免登录, Android 5.1 无证书问题), 失败再退回 gequbao。
        MusicPlayResult kw = kuwoSearchAndGet(keyword);
        if (kw != null) {
            return kw;
        }
        LogMgr.d(TAG, "kuwo failed, fallback to gequbao");
        List<MusicSearchResult> results = search(keyword);
        if (results == null || results.isEmpty()) {
            LogMgr.e(TAG, "searchAndGetFirst: no results");
            return null;
        }

        int limit = Math.min(MAX_CANDIDATES, results.size());
        for (int i = 0; i < limit; i++) {
            MusicSearchResult candidate = results.get(i);
            PlayUrlResult pr = resolvePlayUrl(candidate.id);

            if (pr == null) {
                LogMgr.d(TAG, "searchAndGetFirst: resolve failed for " + candidate.name);
                continue;
            }
            if (pr.captchaRequired || pr.url == null) {
                LogMgr.d(TAG, "searchAndGetFirst: captcha needed, try next: " + candidate.name);
                continue;
            }

            MusicPlayResult result = new MusicPlayResult();
            result.name = candidate.name;
            result.artist = candidate.artist;
            result.album = candidate.album;
            result.playUrl = pr.url;
            result.duration = candidate.duration;
            result.picUrl = candidate.picUrl;
            LogMgr.d(TAG, "searchAndGetFirst ok: " + result.name + " - " + result.artist);
            return result;
        }

        LogMgr.e(TAG, "searchAndGetFirst: no playable candidate");
        return null;
    }

    // ── 酷我 ──────────────────────────────────────────────────────────
    private static final String KUWO_SEARCH =
        "http://search.kuwo.cn/r.s?ft=music&itemset=web_2013&client=kt&pn=0&rn=20"
        + "&rformat=json&encoding=utf8&all=";
    private static final String KUWO_URL =
        "http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&response=url&rid=";
    private static final Pattern KW_RID = Pattern.compile("'MUSICRID':'(MUSIC_\\d+)'");
    private static final Pattern KW_NAME = Pattern.compile("'NAME':'([^']*)'");
    private static final Pattern KW_ARTIST = Pattern.compile("'ARTIST':'([^']*)'");
    private static final Pattern KW_DURATION = Pattern.compile("'DURATION':'(\\d+)'");

    /** 泛指类关键词(儿歌/故事...)随机挑一首, 免得每次都是同一首 */
    private static final String[] GENERIC_WORDS = {"儿歌", "童谣", "儿童歌曲", "宝宝歌", "故事", "胎教", "摇篮曲", "催眠曲", "歌", "音乐", "纯音乐"};

    private final java.util.Random random = new java.util.Random();

    /** 正规儿歌厂牌, 泛指「儿歌」时优先 */
    private static final String[] KID_BRANDS = {"贝乐虎", "儿歌多多", "宝宝巴士", "小伴龙", "贝瓦", "环尼宝贝", "巧虎", "碰碰狐", "小猪佩奇"};
    /** 小朋友听着不合适的版本 */
    private static final String[] BAD_WORDS = {"DJ", "dj", "串烧", "Remix", "remix", "伴奏", "铃声", "抖音", "加速", "降速"};

    /**
     * 酷我搜索, 返回候选列表(未解链, 直链有时效, 播到哪首再解哪首)。
     * 泛指词(儿歌/童谣...): 过滤 DJ/串烧等, 厂牌优先, 再打乱顺序 —— 用于连播。
     */
    public List<MusicSearchResult> kuwoCandidates(String keyword) {
        List<MusicSearchResult> cands = new ArrayList<MusicSearchResult>();
        try {
            String body = httpGet(KUWO_SEARCH + urlEncode(keyword), 2);
            if (body == null || body.isEmpty()) {
                LogMgr.e(TAG, "kuwo search: empty");
                return cands;
            }
            int list = body.indexOf("'abslist'");
            if (list < 0) {
                LogMgr.e(TAG, "kuwo search: no abslist");
                return cands;
            }
            String[] items = body.substring(list).split("\\{'AARTIST'");
            for (String it : items) {
                Matcher rid = KW_RID.matcher(it);
                if (!rid.find()) continue;
                MusicSearchResult r = new MusicSearchResult();
                r.id = rid.group(1);
                Matcher m = KW_NAME.matcher(it);
                r.name = m.find() ? htmlUnescape(m.group(1)).trim() : keyword;
                m = KW_ARTIST.matcher(it);
                r.artist = m.find() ? htmlUnescape(m.group(1)).replace("&", "、").trim() : "";
                m = KW_DURATION.matcher(it);
                if (m.find()) {
                    try { r.duration = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
                }
                cands.add(r);
            }
        } catch (Exception e) {
            LogMgr.e(TAG, "kuwo search error: " + e);
        }
        cands = genericFilter(keyword, cands);
        LogMgr.d(TAG, "kuwo search '" + keyword + "': " + cands.size() + " candidates");
        return cands;
    }

    /** 泛指词(儿歌/童谣...): 过滤 DJ/串烧等, 厂牌优先, 再打乱 —— 酷我和 QQ 共用。 */
    private List<MusicSearchResult> genericFilter(String keyword, List<MusicSearchResult> cands) {
        if (!isGeneric(keyword) || cands.isEmpty()) return cands;
        List<MusicSearchResult> brand = new ArrayList<MusicSearchResult>();
        List<MusicSearchResult> other = new ArrayList<MusicSearchResult>();
        for (MusicSearchResult r : cands) {
            String all = r.name + " " + r.artist;
            if (containsAny(all, BAD_WORDS) || r.name.contains("|")) continue;
            if (containsAny(all, KID_BRANDS)) brand.add(r); else other.add(r);
        }
        java.util.Collections.shuffle(brand, random);
        java.util.Collections.shuffle(other, random);
        brand.addAll(other);
        return brand.isEmpty() ? cands : brand;
    }

    // ── 统一入口: QQ 音乐(有会员凭证且未被拒) 优先, 否则酷我/GD/gequbao ──────────
    private final QQMusicClient qq = QQMusicClient.get();

    /** 候选列表: QQ 可用就用 QQ 搜索结果, 搜不到/不可用退回酷我。 */
    public List<MusicSearchResult> candidates(String keyword) {
        if (qq.available()) {
            List<MusicSearchResult> l = qq.search(keyword, isGeneric(keyword) ? 30 : 20);
            if (!l.isEmpty()) {
                l = genericFilter(keyword, l);
                LogMgr.d(TAG, "candidates src=qq '" + keyword + "': " + l.size());
                return l;
            }
            LogMgr.d(TAG, "qq search empty, fallback kuwo");
        }
        return kuwoCandidates(keyword);
    }

    /** 给候选解直链: QQ 候选先走 QQ vkey, 不行再按「歌名 歌手」去酷我链路找同一首。 */
    public String resolve(MusicSearchResult c) {
        if (c == null) return null;
        if ("qq".equals(c.source)) {
            String u = qq.resolve(c);
            if (u != null) return u;
            return qqMissKuwoChain(c);
        }
        return kuwoResolve(c);
    }

    /**
     * QQ 候选拿不到 QQ 直链时的原有回退: 按「歌名 歌手」去酷我链路(车机FLAC→GD→酷我MP3)找同一首。
     * (从 resolve 拆出来, 逻辑不变; 首曲并行试过 QQ 后直接走这里, 不再重复问一次 QQ。)
     */
    public String qqMissKuwoChain(MusicSearchResult c) {
        {
            long t0 = System.currentTimeMillis();
            String kw = c.name + (c.artist == null || c.artist.isEmpty() ? "" : " " + firstArtistOf(c.artist));
            List<MusicSearchResult> k = kuwoCandidates(kw);
            for (int i = 0; i < k.size() && i < 3; i++) {
                String ku = kuwoResolve(k.get(i));
                if (ku != null) {
                    LogMgr.d(TAG, "qq miss -> kuwo chain ok in " + (System.currentTimeMillis() - t0)
                            + "ms: " + c.name);
                    return ku;
                }
            }
            LogMgr.d(TAG, "qq miss and kuwo chain miss: " + c.name);
            return null;
        }
    }

    private static String firstArtistOf(String artist) {
        String[] parts = artist.split("[&/、,，;；]");
        return parts.length == 0 ? artist : parts[0].trim();
    }

    /** 给一首候选解出可播直链; 无版权(11 秒提示音)或失败返回 null。 */
    public String kuwoResolve(MusicSearchResult c) {
        // 先试无损: 酷我车机 FLAC -> GD 台网易云 FLAC/320k; 都不行再走原来的酷我标准 MP3
        String hq = losslessOnly(c);
        if (hq != null) return hq;
        return kuwoMp3Only(c);
    }

    /** kuwoResolve 的第 1 段(v76 拆出, 逻辑不变): 无损 酷我车机 FLAC -> GD 台; 关闭/失败返回 null。 */
    public String losslessOnly(MusicSearchResult c) {
        if (!losslessEnabled) return null;
        try {
            return lossless.resolve(c.id, c.name, c.artist);
        } catch (Throwable t) {
            LogMgr.e(TAG, "lossless error: " + t);
            return null;
        }
    }

    /**
     * v76 预热(后台线程): 酷我搜索/直链域名 DNS; 没有 QQ 会员(走酷我链路)时再预热 GD 台 TLS 连接。
     * 不发任何 API 请求, 不改变回退链。
     */
    public void prewarm() {
        long t0 = System.currentTimeMillis();
        for (String h : new String[]{"search.kuwo.cn", "antiserver.kuwo.cn"}) {
            try { java.net.InetAddress.getAllByName(h); } catch (Throwable ignored) { }
        }
        LogMgr.d(TAG, "warm-up: dns kuwo " + (System.currentTimeMillis() - t0) + "ms");
        boolean qqOn = false;
        try { qqOn = qq.available(); } catch (Throwable ignored) { }
        if (losslessEnabled) lossless.warm(!qqOn);
    }

    /** kuwoResolve 的第 2 段(v76 拆出, 逻辑不变): 原来的酷我标准 MP3。 */
    public String kuwoMp3Only(MusicSearchResult c) {
        String url = httpGet(KUWO_URL + c.id, 2);
        if (url == null) return null;
        url = url.trim();
        if (!url.startsWith("http")) {
            LogMgr.d(TAG, "kuwo url bad for " + c.name + ": " + url);
            return null;
        }
        // 没版权/会员曲, 酷我不报错而是给一段 11 秒的固定提示音(588957081.mp3), 要跳过
        if (url.contains("/588957081.mp3")) {
            LogMgr.d(TAG, "kuwo placeholder (no rights), skip: " + c.name);
            return null;
        }
        if (url.startsWith("https://")) url = "http://" + url.substring(8);
        LogMgr.d(TAG, "resolved src=kuwo-anti q=mp3(standard): " + c.name);
        return url;
    }

    private final LosslessResolver lossless = new LosslessResolver();
    /** 无损开关: 设备带宽/解码出问题时可改 false 回到纯 MP3 */
    public static volatile boolean losslessEnabled = true;

    public static MusicPlayResult toPlay(MusicSearchResult c, String url) {
        MusicPlayResult res = new MusicPlayResult();
        res.name = c.name;
        res.artist = c.artist;
        res.playUrl = url;
        res.duration = c.duration;
        res.cand = c;
        res.resolvedAt = System.currentTimeMillis();
        return res;
    }

    private static boolean isGeneric(String keyword) {
        for (String g : GENERIC_WORDS) if (g.equals(keyword)) return true;
        return false;
    }

    private static boolean containsAny(String s, String[] words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private MusicPlayResult kuwoSearchAndGet(String keyword) {
        List<MusicSearchResult> cands = kuwoCandidates(keyword);
        int tries = Math.min(12, cands.size());
        for (int i = 0; i < tries; i++) {
            String url = kuwoResolve(cands.get(i));
            if (url != null) {
                MusicPlayResult res = toPlay(cands.get(i), url);
                LogMgr.d(TAG, "kuwo ok: " + res.name + " - " + res.artist + " url=" + url);
                return res;
            }
        }
        return null;
    }

    /**
     * 从返回体里抠 "url":"..."，并还原转义。
     */
    private String extractJsonUrl(String json) {
        int urlStart = json.indexOf("\"url\":\"");
        if (urlStart == -1) {
            return null;
        }
        urlStart += 7; // 跳过 "url":"
        int urlEnd = json.indexOf("\"", urlStart);
        if (urlEnd == -1) {
            return null;
        }
        String playUrl = json.substring(urlStart, urlEnd);
        // JSON 中 URL 可能包含转义字符：\u0026 -> &, \/ -> /, \u003d -> =
        playUrl = playUrl.replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "=");
        // 设备端（Android 5.1）信任库老旧，直链 https 可能验不过证书，降级为 http。
        // 酷我 CDN 两种都放行，实测 http 同样返回 206。
        if (playUrl.startsWith("https://")) {
            playUrl = "http://" + playUrl.substring(8);
        }
        LogMgr.d(TAG, "playUrl: " + playUrl);
        return playUrl;
    }

    private String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * 最小 HTML 实体解码（Android 5.1 上不用 Apache 的 StringEscapeUtils）
     */
    private String htmlUnescape(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
    }

    /** HTTP GET，带退避重试。 */
    private String httpGet(String urlStr) {
        return httpGet(urlStr, MAX_RETRY);
    }

    private String httpGet(String urlStr, int retries) {
        for (int attempt = 0; attempt < retries; attempt++) {
            String r = httpGetOnce(urlStr);
            if (r != null) {
                return r;
            }
            if (attempt < retries - 1) {
                sleepQuietly(600L * (attempt + 1));
            }
        }
        LogMgr.d(TAG, "httpGet exhausted: " + urlStr);
        return null;
    }

    private String httpGetOnce(String urlStr) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        boolean clean = false;

        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoInput(true);

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                LogMgr.e(TAG, "httpGet failed: " + responseCode);
                return null;
            }

            StringBuilder response = new StringBuilder();
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            clean = true;   // v76: 读到 EOF, close 后连接回池(酷我 search/antiserver 下一次免重新建连)

            return response.toString();

        } catch (Exception e) {
            LogMgr.e(TAG, "httpGet error: " + e);
            return null;
        } finally {
            closeQuietly(reader);
            if (conn != null && !clean) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * HTTP POST 表单请求。
     * gequbao 前端用 jQuery $.ajax 发表单（不是 JSON），
     * 且缺 X-Requested-With 会被直接拒掉 —— 这是旧版失败的另一个原因。
     */
    private String httpPostForm(String urlStr, String formBody, String referer, int retries) {
        for (int attempt = 0; attempt < retries; attempt++) {
            String r = httpPostFormOnce(urlStr, formBody, referer);
            if (r != null) {
                return r;
            }
            if (attempt < retries - 1) {
                sleepQuietly(600L * (attempt + 1));
            }
        }
        return null;
    }

    private String httpPostFormOnce(String urlStr, String formBody, String referer) {
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        OutputStream os = null;

        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");
            if (referer != null) {
                conn.setRequestProperty("Referer", referer);
            }
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoInput(true);
            conn.setDoOutput(true);

            os = conn.getOutputStream();
            os.write(formBody.getBytes("UTF-8"));
            os.flush();

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                LogMgr.e(TAG, "httpPostForm failed: " + responseCode);
                return null;
            }

            StringBuilder response = new StringBuilder();
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            return response.toString();

        } catch (Exception e) {
            LogMgr.e(TAG, "httpPostForm error: " + e);
            return null;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {}
            }
            closeQuietly(reader);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly(BufferedReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception ignored) {}
        }
    }

    // ========== 内部类 ==========

    /** 直链缓存项 */
    private static class CacheEntry {
        final String url;
        final long expireAt;

        CacheEntry(String url, long expireAt) {
            this.url = url;
            this.expireAt = expireAt;
        }
    }

    /** 取直链的结果（带状态） */
    public static class PlayUrlResult {
        /** 播放直链，失败时为 null */
        public String url;
        /** true 表示 gequbao 要求人机验证（code==2），音箱端只能换一首 */
        public boolean captchaRequired;
    }

    /**
     * 搜索结果（不含播放 URL）
     */
    public static class MusicSearchResult {
        public String id;         // 歌曲 ID（字符串）; QQ 为 songmid
        public String source;     // null=酷我/gequbao, "qq"=QQ 音乐
        public String mediaMid;   // QQ 音乐文件 media_mid
        public String name;       // 歌曲名
        public String artist;     // 歌手
        public String album;      // 专辑
        public String picUrl;     // 封面图 URL
        public long duration;     // 秒

        @Override
        public String toString() {
            return "MusicSearchResult{id=" + id + ", name='" + name + "', artist='" + artist + "'}";
        }
    }

    /**
     * 播放结果（包含所有播放所需信息）
     */
    public static class MusicPlayResult {
        public String name;       // 歌曲名
        public String artist;     // 歌手
        public String album;      // 专辑
        public String playUrl;    // 播放 URL
        public long duration;     // 时长（秒）
        public String picUrl;     // 封面图 URL
        public MusicSearchResult cand;   // 来源候选(重新解链用), gequbao 单曲为 null
        public long resolvedAt;          // 直链解析时间(墙钟)
        /** v76: 首曲 MP3 已可播、无损还在解析时挂在这里(播报先开始, 无损到了再换缓冲); 平时为 null */
        public transient java.util.concurrent.Future<String> pendingHq;

        @Override
        public String toString() {
            return "MusicPlayResult{name='" + name + "', artist='" + artist + "', url='" + playUrl + "'}";
        }
    }
}
