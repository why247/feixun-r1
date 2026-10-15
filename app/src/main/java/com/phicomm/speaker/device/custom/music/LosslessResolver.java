package com.phicomm.speaker.device.custom.music;

import com.unisound.vui.util.LogMgr;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 无损(FLAC)直链解析。按顺序尝试:
 *   1. 酷我车机接口 mobi.kuwo.cn convert_url_with_sign br=flac (同一个 rid, 免登录, 仅中国大陆 IP 可用)
 *   2. GD 音乐台 music-api.gdstudio.xyz 的网易云源: search -> url br=740(FLAC) , 拿不到无损时接受 320k
 * 都失败返回 null, 由调用方继续走原来的酷我 MP3 / gequbao。
 *
 * 只在「要播这一首」时解析(当前曲 + 自动连播的下一首), 不预解析整个队列;
 * GD 台限流 5 分钟 50 次, 这里自己数请求, 到 40 次就暂停用它。
 * 日志标签 MusicClient: "lossless ok src=... q=..." 表示用了哪个源、什么音质。
 */
public class LosslessResolver {
    private static final String TAG = "MusicClient";

    private static final String KW_MOBI =
        "http://mobi.kuwo.cn/mobi.s?f=web&source=kwplayercar_ar_6.0.0.9_B_jiakong_vh.apk"
        + "&from=PC&type=convert_url_with_sign&br=flac&user=C_APK_guanwang_";
    private static final String GD_API = "https://music-api.gdstudio.xyz/api.php";
    /** 不能用 curl 一类的 UA, 否则 Cloudflare 会弹人机验证 */
    private static final String UA = "Mozilla/5.0 (Linux; U; Android 5.1.1; Phicomm R1) AppleWebKit/537.36";

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 8000;
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;     // 网易/酷我直链带时效签名
    private static final long GD_WINDOW_MS = 5 * 60 * 1000L;
    private static final int GD_BUDGET = 40;                       // 官方 50 次/5 分钟, 留余量
    private static final long KW_MOBI_BACKOFF_MS = 30 * 60 * 1000L;

    private final Map<String, String[]> cache = new HashMap<String, String[]>(); // rid -> {url, label, expire}
    private final ArrayDeque<Long> gdCalls = new ArrayDeque<Long>();
    private long kwMobiDisabledUntil = 0;
    private int kwMobiFailStreak = 0;
    private final String userId = String.valueOf(100000000L + (long) (Math.random() * 899999999L));

    /** 返回可直接给 MediaPlayer 的 http 直链, 失败返回 null。 */
    public synchronized String resolve(String kuwoRid, String name, String artist) {
        long now = System.currentTimeMillis();
        String[] hit = cache.get(kuwoRid);
        if (hit != null && Long.parseLong(hit[2]) > now) {
            LogMgr.d(TAG, "lossless cache " + hit[1] + ": " + name);
            return hit[0];
        }
        String url = null;
        String label = null;

        // 1. 酷我车机 FLAC
        if (now >= kwMobiDisabledUntil && kuwoRid != null) {
            url = kuwoMobiFlac(kuwoRid);
            if (url != null) {
                label = "src=kuwo-mobi q=flac";
                kwMobiFailStreak = 0;
            } else if (++kwMobiFailStreak >= 3) {
                // 连续 3 首都拿不到(多半是 IP 地区或接口变了), 半小时内别再浪费这一跳
                kwMobiDisabledUntil = now + KW_MOBI_BACKOFF_MS;
                kwMobiFailStreak = 0;
                LogMgr.d(TAG, "kuwo-mobi disabled for 30 min");
            }
        }

        // 2. GD 音乐台(网易云源)
        if (url == null && name != null && !name.isEmpty()) {
            String[] r = gdNetease(name, artist);
            if (r != null) {
                url = r[0];
                label = "src=gd-netease q=" + r[1];
            }
        }

        if (url == null) {
            LogMgr.d(TAG, "lossless miss, fallback mp3: " + name);
            return null;
        }
        if (url.startsWith("https://")) url = "http://" + url.substring(8); // 两家 CDN http 都返回 206, 避开 5.1 证书问题
        cache.put(kuwoRid, new String[]{url, label, String.valueOf(now + CACHE_TTL_MS)});
        if (cache.size() > 64) cache.clear();
        LogMgr.d(TAG, "lossless ok " + label + ": " + name + " - " + artist);
        return url;
    }

    private String kuwoMobiFlac(String kuwoRid) {
        String rid = kuwoRid.startsWith("MUSIC_") ? kuwoRid.substring(6) : kuwoRid;
        String body = get(KW_MOBI + userId + "&rid=" + rid, false);
        if (body == null) return null;
        try {
            JSONObject o = new JSONObject(body);
            if (o.optInt("code") != 200) {
                LogMgr.d(TAG, "kuwo-mobi code=" + o.optInt("code") + " " + o.optString("msg"));
                return null;
            }
            JSONObject d = o.optJSONObject("data");
            if (d == null) return null;
            String url = d.optString("url", "");
            String fmt = d.optString("format", "");
            if (!url.startsWith("http")) return null;
            if (!"flac".equalsIgnoreCase(fmt)) {
                LogMgr.d(TAG, "kuwo-mobi gave " + fmt + " " + d.optInt("bitrate") + "k, not flac");
                return null;
            }
            return url;
        } catch (Exception e) {
            LogMgr.d(TAG, "kuwo-mobi parse: " + e);
            return null;
        }
    }

    /** @return {url, 音质标签} */
    private String[] gdNetease(String name, String artist) {
        if (!gdBudget(2)) {
            LogMgr.d(TAG, "gd budget exhausted, skip");
            return null;
        }
        String q = name + (artist == null || artist.isEmpty() ? "" : " " + firstArtist(artist));
        String body = get(GD_API + "?types=search&source=netease&count=8&name=" + enc(q), true);
        if (body == null) return null;
        String id = null;
        try {
            JSONArray arr = new JSONArray(body);
            id = pickBest(arr, name, artist);
        } catch (Exception e) {
            LogMgr.d(TAG, "gd search parse: " + e);
            return null;
        }
        if (id == null) {
            LogMgr.d(TAG, "gd: no exact match for " + name + " - " + artist);
            return null;
        }
        // br=740 拿 16bit FLAC; 不用 999(可能是 24bit Hi-Res, 5.1 解码/带宽没必要)
        body = get(GD_API + "?types=url&source=netease&br=740&id=" + id, true);
        if (body == null) return null;
        try {
            JSONObject o = new JSONObject(body);
            String url = o.optString("url", "");
            int br = o.optInt("br", 0);
            if (!url.startsWith("http")) {
                LogMgr.d(TAG, "gd url empty for id=" + id);
                return null;
            }
            String quality = url.contains(".flac") || br >= 700 ? "flac(" + br + "k)" : br + "k";
            if (br > 0 && br < 300) {
                // 只有 128k 时不如酷我原来的链路, 让调用方回落
                LogMgr.d(TAG, "gd only " + br + "k, skip");
                return null;
            }
            return new String[]{url, quality};
        } catch (Exception e) {
            LogMgr.d(TAG, "gd url parse: " + e);
            return null;
        }
    }

    /**
     * 只认「歌名一致(去掉括号版本说明) + 歌手名完全相同」, 翻唱/伴奏/Live 不要;
     * 宁可回落酷我 MP3, 也不播错版本(GD 台搜索常把翻唱排在原唱前面)。
     */
    static String pickBest(JSONArray arr, String name, String artist) throws Exception {
        String n = norm(name);
        java.util.List<String> want = new java.util.ArrayList<String>();
        if (artist != null) {
            for (String x : artist.split("[、&/,，]")) {
                if (!x.trim().isEmpty()) want.add(x.trim().toLowerCase());
            }
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.getJSONObject(i);
            String raw = s.optString("name");
            String low = raw.toLowerCase();
            if (low.contains("cover") || raw.contains("翻唱") || raw.contains("翻自") || raw.contains("伴奏")
                    || low.contains("live") || low.contains("remix") || low.contains("dj")) continue;
            if (!n.equals(norm(raw))) continue;
            if (want.isEmpty()) return s.optString("id");
            JSONArray ar = s.optJSONArray("artist");
            if (ar == null) continue;
            for (int k = 0; k < ar.length(); k++) {
                if (want.contains(ar.optString(k).trim().toLowerCase())) return s.optString("id");
            }
        }
        return null;
    }

    private static String firstArtist(String artist) {
        String a = artist.trim();
        for (String sep : new String[]{"、", "&", "/", ",", "，"}) {
            int p = a.indexOf(sep);
            if (p > 0) a = a.substring(0, p);
        }
        return a.trim();
    }

    /** 去掉括号里的版本说明和空白, 用于比较歌名 */
    private static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[(（\\[【].*?[)）\\]】]", "").replaceAll("\\s+", "").toLowerCase();
    }

    private boolean gdBudget(int need) {
        long now = System.currentTimeMillis();
        while (!gdCalls.isEmpty() && now - gdCalls.peekFirst() > GD_WINDOW_MS) gdCalls.pollFirst();
        if (gdCalls.size() + need > GD_BUDGET) return false;
        for (int i = 0; i < need; i++) gdCalls.addLast(now);
        return true;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private String get(String urlStr, boolean allowInsecureRetry) {
        try {
            return getOnce(urlStr, false);
        } catch (SSLException e) {
            if (!allowInsecureRetry) return null;
            // Android 5.1 信任库老; 这里只拿一个公开的播放直链, 不带任何凭证, 证书验不过就放宽重试一次
            LogMgr.d(TAG, "ssl failed (" + e.getMessage() + "), retry relaxed");
            try {
                return getOnce(urlStr, true);
            } catch (Exception e2) {
                LogMgr.d(TAG, "get failed: " + e2);
                return null;
            }
        } catch (Exception e) {
            LogMgr.d(TAG, "get failed: " + e);
            return null;
        }
    }

    private String getOnce(String urlStr, boolean relaxed) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            if (relaxed && conn instanceof HttpsURLConnection) {
                HttpsURLConnection hc = (HttpsURLConnection) conn;
                hc.setSSLSocketFactory(relaxedContext().getSocketFactory());
                hc.setHostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String h, SSLSession s) {
                        return h != null && h.endsWith("gdstudio.xyz");
                    }
                });
            }
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json,*/*");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            int code = conn.getResponseCode();
            if (code != 200) {
                LogMgr.d(TAG, "http " + code + " " + hostOf(urlStr));
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 256 * 1024) break;
            }
            in.close();
            return bos.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private static String hostOf(String u) {
        try {
            return new URL(u).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    private static SSLContext relaxed;

    private static synchronized SSLContext relaxedContext() throws Exception {
        if (relaxed == null) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] c, String t) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] c, String t) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            relaxed = ctx;
        }
        return relaxed;
    }
}
