package com.phicomm.speaker.device.custom.music;

import com.unisound.vui.util.LogMgr;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * QQ 音乐(会员)官方接口: 搜索 + 取播放直链 + musickey 自动续期。
 *
 * 凭证: files/qq_cred.json (电脑上 tools/qq_login.py 扫码生成并 run-as 推入, 只存在 app 私有目录)
 *   musicid, musickey, refresh_key, refresh_token, loginType(1=微信 2=QQ), saved_at(ms) ...
 *
 * 接口(u.y.qq.com/cgi-bin/musicu.fcg, Android 5.1 TLS 老, 一律走 http):
 *   搜索  music.search.SearchCgiService.DoSearchForQQMusicMobile  (失败退 c.y.qq.com client_search_cp)
 *   直链  vkey.GetVkeyServer.CgiGetVkey  comm{uin, authst=musickey, tmeLoginType}  F000 -> M800 -> M500
 *   续期  music.login.LoginServer.Login  {refresh_key, refresh_token, musickey, musicid, loginMode=2}
 * 参考 L-1124/QQMusicApi(login.refresh_credential) 与 Nshpiter/Q-music(qqCredentials.ts / musicAccount.ts)。
 *
 * 凭证被拒(1000/104400/104401) -> rejected=true, 后台尝试续期; 期间 available()=false, 上层退回酷我链路。
 * 日志绝不打印凭证内容, 只打印长度。
 */
public final class QQMusicClient {
    private static final String TAG = "MusicClient";
    private static final String CRED_FILE = "qq_cred.json";
    private static final String MUSICU = "http://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String SEARCH_CP =
            "http://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&cr=1&new_json=1&p=1&n=";
    private static final String UA = "Mozilla/5.0 (Linux; U; Android 5.1.1; Phicomm R1) AppleWebKit/537.36";
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 8000;

    private static final long REFRESH_EVERY_MS = 24L * 3600 * 1000;
    private static final long STALE_BEFORE_USE_MS = 20L * 3600 * 1000;
    private static final long BOOT_REFRESH_MIN_AGE_MS = 3600 * 1000L;   // 频繁重启时别每次都刷
    private static final long RETRY_FAILED_MS = 3600 * 1000L;
    private static final long MIN_ATTEMPT_GAP_MS = 10 * 60 * 1000L;

    /** 音质降级顺序: 无损 flac -> 320k mp3 -> 128k mp3 */
    private static final String[][] QUALITIES = {
            {"F000", ".flac", "flac"},
            {"M800", ".mp3", "320"},
            {"M500", ".mp3", "128"},
    };

    private static final QQMusicClient INSTANCE = new QQMusicClient();

    public static QQMusicClient get() {
        return INSTANCE;
    }

    private File dir;
    private JSONObject cred;
    private long credMtime = 0;
    private volatile boolean rejected = false;
    private long lastAttempt = 0;
    private Thread refresher;
    private final Random rnd = new Random();

    private QQMusicClient() {
    }

    /** 开机时调用一次(拿 files 目录, 起每日续期线程)。 */
    public synchronized void init(File filesDir) {
        if (filesDir == null) return;
        this.dir = filesDir;
        load();
        if (refresher != null) return;
        refresher = new Thread(new Runnable() {
            @Override
            public void run() {
                refreshLoop();
            }
        }, "QQMusicRefresh");
        refresher.setDaemon(true);
        refresher.start();
    }

    /** 有凭证且没被服务器拒绝。凭证文件被重新推送(mtime 变化)时自动重载并解除拒绝状态。 */
    public synchronized boolean available() {
        reloadIfChanged();
        return cred != null && !rejected;
    }

    // ------------------------------------------------------------------ 凭证文件
    private File file() {
        return dir == null ? null : new File(dir, CRED_FILE);
    }

    private void reloadIfChanged() {
        File f = file();
        if (f == null) return;
        if (!f.isFile()) {
            if (cred != null) LogMgr.d(TAG, "qq cred file removed");
            cred = null;
            return;
        }
        if (f.lastModified() != credMtime) load();
    }

    private void load() {
        File f = file();
        if (f == null || !f.isFile()) {
            cred = null;
            LogMgr.d(TAG, "qq cred: none (run tools/qq_login.py to enable QQ Music)");
            return;
        }
        try {
            JSONObject o = new JSONObject(readText(f));
            if (o.optString("musickey", "").isEmpty() || musicid(o).isEmpty()) {
                LogMgr.e(TAG, "qq cred: missing musicid/musickey");
                cred = null;
                return;
            }
            cred = o;
            credMtime = f.lastModified();
            rejected = false;
            LogMgr.d(TAG, "qq cred loaded: loginType=" + loginType(o)
                    + " key_len=" + o.optString("musickey").length()
                    + " refresh_key=" + (o.optString("refresh_key", "").isEmpty() ? "no" : "yes")
                    + " age_h=" + (ageMs(o) / 3600000L));
        } catch (Exception e) {
            LogMgr.e(TAG, "qq cred: bad file: " + e.getClass().getSimpleName());
            cred = null;
        }
    }

    private void save(JSONObject o) throws Exception {
        File tmp = new File(dir, CRED_FILE + ".tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        try {
            fo.write(o.toString().getBytes("UTF-8"));
            fo.getFD().sync();
        } finally {
            fo.close();
        }
        tmp.setReadable(false, false);
        tmp.setWritable(false, false);
        tmp.setReadable(true, true);
        tmp.setWritable(true, true);
        if (!tmp.renameTo(new File(dir, CRED_FILE))) throw new Exception("rename failed");
        credMtime = new File(dir, CRED_FILE).lastModified();
    }

    private static String musicid(JSONObject o) {
        String id = o.optString("musicid", "");
        if (id.isEmpty() || "0".equals(id)) id = o.optString("str_musicid", "");
        return id;
    }

    private static int loginType(JSONObject o) {
        int t = o.optInt("loginType", 0);
        if (t == 0) t = o.optString("musickey", "").startsWith("W_X") ? 1 : 2;
        return t;
    }

    private static long ageMs(JSONObject o) {
        long saved = o.optLong("saved_at", 0);
        return saved <= 0 ? Long.MAX_VALUE / 4 : System.currentTimeMillis() - saved;
    }

    // ------------------------------------------------------------------ 续期
    private void refreshLoop() {
        try {
            Thread.sleep(30_000);   // 等开机联网
        } catch (InterruptedException e) {
            return;
        }
        boolean first = true;
        while (true) {
            long sleep = REFRESH_EVERY_MS;
            try {
                JSONObject c;
                synchronized (this) {
                    reloadIfChanged();
                    c = cred;
                }
                if (c != null) {
                    long age = ageMs(c);
                    if (!first || age >= BOOT_REFRESH_MIN_AGE_MS || rejected) {
                        if (!refresh("daily")) sleep = RETRY_FAILED_MS;
                    } else {
                        sleep = Math.max(60_000L, REFRESH_EVERY_MS - age);
                    }
                }
            } catch (Throwable t) {
                LogMgr.e(TAG, "qq refresh loop: " + t);
                sleep = RETRY_FAILED_MS;
            }
            first = false;
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * 用前检查: 凭证超过 20 小时就在后台续期一次(点歌路径绝不同步续期, 这次照常用旧 key)。
     */
    private void refreshIfStaleAsync() {
        JSONObject c = cred;
        if (c == null) return;
        if (ageMs(c) < STALE_BEFORE_USE_MS) return;
        refreshAsync("stale");
    }

    private void refreshAsync(final String why) {
        if (System.currentTimeMillis() - lastAttempt < MIN_ATTEMPT_GAP_MS) return;
        if (refreshing.get()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                refresh(why);
            }
        }, "QQMusicRefreshNow").start();
    }

    /** 正在续期(同一时刻只跑一个) */
    private final java.util.concurrent.atomic.AtomicBoolean refreshing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * music.login.LoginServer.Login loginMode=2 刷新 musickey, 成功原子写回 qq_cred.json。
     * 网络请求不持有对象锁(不挡 available()/search()/resolve()), 只在换上新凭证时短暂加锁。
     * 只由后台线程调用(每日续期线程 / refreshAsync)。
     */
    public boolean refresh(String why) {
        if (!refreshing.compareAndSet(false, true)) {
            LogMgr.d(TAG, "qq refresh skipped (" + why + "): already running");
            return false;
        }
        try {
            return refreshInner(why);
        } finally {
            refreshing.set(false);
        }
    }

    private boolean refreshInner(String why) {
        JSONObject c;
        synchronized (this) {
            lastAttempt = System.currentTimeMillis();
            c = cred;
        }
        if (c == null) return false;
        if (c.optString("refresh_key", "").isEmpty()) {
            LogMgr.d(TAG, "qq refresh skipped (" + why + "): no refresh_key, rescan when expired");
            return false;
        }
        long t0 = System.currentTimeMillis();
        try {
            int lt = loginType(c);
            JSONObject p = new JSONObject();
            p.put("refresh_key", c.optString("refresh_key"));
            p.put("refresh_token", c.optString("refresh_token", ""));
            p.put("musickey", c.optString("musickey"));
            p.put("openid", c.optString("openid", ""));
            p.put("loginMode", 2);
            if (lt == 1) {   // 微信
                p.put("str_musicid", musicid(c));
                p.put("unionid", c.optString("unionid", ""));
            } else {         // QQ
                p.put("musicid", parseLong(musicid(c)));
                p.put("access_token", c.optString("access_token", ""));
                p.put("expired_in", c.optLong("expired_at", 0));
            }
            JSONObject comm = new JSONObject();
            comm.put("ct", "11");
            comm.put("cv", "13020508");
            comm.put("v", "13020508");
            comm.put("tmeAppID", "qqmusic");
            comm.put("format", "json");
            comm.put("inCharset", "utf-8");
            comm.put("outCharset", "utf-8");
            comm.put("qq", musicid(c));
            comm.put("authst", c.optString("musickey"));
            comm.put("tmeLoginType", String.valueOf(lt));
            JSONObject req = call(comm, "music.login.LoginServer", "Login", p, c);
            int code = req == null ? -1 : req.optInt("code", -1);
            JSONObject data = req == null ? null : req.optJSONObject("data");
            if (code != 0 || data == null || data.optString("musickey", "").isEmpty()) {
                LogMgr.e(TAG, "qq refresh failed (" + why + "): code=" + code
                        + " in " + (System.currentTimeMillis() - t0) + "ms, keep old key");
                return false;
            }
            JSONObject n = new JSONObject(c.toString());
            String[] keys = {"musicid", "musickey", "refresh_key", "refresh_token", "access_token", "openid",
                    "unionid", "str_musicid", "expired_at", "encryptUin", "loginType",
                    "keyExpiresIn", "musickeyCreateTime"};
            for (String k : keys) {
                Object v = data.opt(k);
                if (v == null || JSONObject.NULL.equals(v)) continue;
                String s = String.valueOf(v);
                if (s.isEmpty() || "0".equals(s)) continue;
                n.put(k, v);
            }
            n.put("saved_at", System.currentTimeMillis());
            synchronized (this) {
                save(n);
                cred = n;
                rejected = false;
            }
            LogMgr.d(TAG, "qq refresh ok (" + why + ") in " + (System.currentTimeMillis() - t0)
                    + "ms, key_len=" + n.optString("musickey").length());
            return true;
        } catch (Throwable t) {
            LogMgr.e(TAG, "qq refresh error (" + why + "): " + t.getClass().getSimpleName()
                    + " in " + (System.currentTimeMillis() - t0) + "ms, keep old key");
            return false;
        }
    }

    // ------------------------------------------------------------------ 搜索
    /** 搜索, 返回候选(source="qq", id=songmid, mediaMid)。失败返回空列表。 */
    public List<NetEaseMusicClient.MusicSearchResult> search(String keyword, int n) {
        long t0 = System.currentTimeMillis();
        List<NetEaseMusicClient.MusicSearchResult> out = new ArrayList<NetEaseMusicClient.MusicSearchResult>();
        String uin;
        synchronized (this) {
            uin = cred == null ? "0" : musicid(cred);
        }
        try {
            JSONObject comm = new JSONObject();
            comm.put("ct", 19);
            comm.put("cv", 1859);
            comm.put("uin", uin);
            JSONObject p = new JSONObject();
            p.put("searchid", String.valueOf(10000000000000L + (long) (rnd.nextDouble() * 89999999999999L)));
            p.put("query", keyword);
            p.put("search_type", 0);
            p.put("num_per_page", n);
            p.put("page_num", 1);
            p.put("highlight", 0);
            p.put("grp", 1);
            JSONObject req = call(comm, "music.search.SearchCgiService", "DoSearchForQQMusicMobile", p, null);
            if (req != null && req.optInt("code", -1) == 0) {
                JSONObject body = req.optJSONObject("data") == null ? null
                        : req.optJSONObject("data").optJSONObject("body");
                JSONArray list = body == null ? null : body.optJSONArray("item_song");
                if ((list == null || list.length() == 0) && body != null && body.optJSONObject("song") != null)
                    list = body.optJSONObject("song").optJSONArray("list");
                parseSongs(list, out);
            }
        } catch (Throwable t) {
            LogMgr.d(TAG, "qq search(musicu) error: " + t.getClass().getSimpleName());
        }
        if (out.isEmpty()) {
            try {
                String s = httpGet(SEARCH_CP + n + "&w=" + URLEncoder.encode(keyword, "UTF-8"));
                if (s != null) {
                    JSONObject j = new JSONObject(s);
                    JSONObject data = j.optJSONObject("data");
                    JSONObject song = data == null ? null : data.optJSONObject("song");
                    parseSongs(song == null ? null : song.optJSONArray("list"), out);
                }
            } catch (Throwable t) {
                LogMgr.d(TAG, "qq search(cp) error: " + t.getClass().getSimpleName());
            }
        }
        LogMgr.d(TAG, "qq search '" + keyword + "': " + out.size() + " in "
                + (System.currentTimeMillis() - t0) + "ms");
        return out;
    }

    private static void parseSongs(JSONArray list, List<NetEaseMusicClient.MusicSearchResult> out) {
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            String mid = s.optString("mid", "");
            JSONObject file = s.optJSONObject("file");
            String media = file == null ? "" : file.optString("media_mid", "");
            if (media.isEmpty() && file != null) media = file.optString("strMediaMid", "");
            if (mid.isEmpty()) continue;
            NetEaseMusicClient.MusicSearchResult r = new NetEaseMusicClient.MusicSearchResult();
            r.source = "qq";
            r.id = mid;
            r.mediaMid = media.isEmpty() ? mid : media;
            r.name = stripTags(s.optString("name", s.optString("title", ""))).trim();
            StringBuilder ar = new StringBuilder();
            JSONArray singers = s.optJSONArray("singer");
            if (singers != null) {
                for (int k = 0; k < singers.length(); k++) {
                    JSONObject sg = singers.optJSONObject(k);
                    if (sg == null) continue;
                    String nm = sg.optString("name", "").trim();
                    if (nm.isEmpty()) continue;
                    if (ar.length() > 0) ar.append("、");
                    ar.append(nm);
                }
            }
            r.artist = ar.toString();
            JSONObject album = s.optJSONObject("album");
            r.album = album == null ? "" : album.optString("name", "");
            r.duration = s.optLong("interval", 0);
            if (r.name.isEmpty()) continue;
            out.add(r);
        }
    }

    private static String stripTags(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "");
    }

    // ------------------------------------------------------------------ 直链
    /**
     * 取播放直链: F000(flac) -> M800(320k) -> M500(128k), 一次请求问三档。
     * 返回 http 直链; 无权限/失败返回 null(上层退回酷我)。
     */
    public String resolve(NetEaseMusicClient.MusicSearchResult c) {
        if (c == null || c.id == null) return null;
        JSONObject cr;
        synchronized (this) {
            reloadIfChanged();
            if (cred == null || rejected) return null;
            refreshIfStaleAsync();
            cr = cred;
        }
        long t0 = System.currentTimeMillis();
        try {
            String uin = musicid(cr);
            String media = c.mediaMid == null || c.mediaMid.isEmpty() ? c.id : c.mediaMid;
            JSONArray mids = new JSONArray();
            JSONArray types = new JSONArray();
            JSONArray files = new JSONArray();
            for (String[] q : QUALITIES) {
                mids.put(c.id);
                types.put(0);
                files.put(q[0] + media + q[1]);
            }
            JSONObject p = new JSONObject();
            p.put("guid", String.valueOf(1000000000L + (long) (rnd.nextDouble() * 8999999999L)));
            p.put("songmid", mids);
            p.put("songtype", types);
            p.put("filename", files);
            p.put("loginflag", 1);
            p.put("platform", "20");
            p.put("uin", uin);
            JSONObject comm = new JSONObject();
            comm.put("uin", uin);
            comm.put("authst", cr.optString("musickey"));
            comm.put("ct", 24);
            comm.put("cv", 0);
            comm.put("format", "json");
            comm.put("tmeAppID", "qqmusic");
            comm.put("tmeLoginType", loginType(cr));
            JSONObject req = call(comm, "vkey.GetVkeyServer", "CgiGetVkey", p, cr);
            long ms = System.currentTimeMillis() - t0;
            if (req == null) {
                LogMgr.d(TAG, "qq vkey: no response in " + ms + "ms: " + c.name);
                return null;
            }
            int code = req.optInt("code", -1);
            if (code == 1000 || code == 104400 || code == 104401) {
                synchronized (this) {
                    rejected = true;
                }
                LogMgr.e(TAG, "qq vkey: credential rejected code=" + code + ", fallback kuwo; refreshing");
                refreshAsync("rejected");
                return null;
            }
            JSONObject data = req.optJSONObject("data");
            if (code != 0 || data == null) {
                LogMgr.d(TAG, "qq vkey: code=" + code + " in " + ms + "ms: " + c.name);
                return null;
            }
            String sip = "";
            JSONArray sips = data.optJSONArray("sip");
            if (sips != null) {
                for (int i = 0; i < sips.length() && sip.isEmpty(); i++) sip = sips.optString(i, "");
            }
            if (sip.isEmpty()) sip = "http://ws.stream.qqmusic.qq.com/";
            JSONArray infos = data.optJSONArray("midurlinfo");
            for (String[] q : QUALITIES) {
                String fn = q[0] + media + q[1];
                if (infos == null) break;
                for (int i = 0; i < infos.length(); i++) {
                    JSONObject in = infos.optJSONObject(i);
                    if (in == null || !fn.equals(in.optString("filename"))) continue;
                    String purl = in.optString("purl", "");
                    if (purl.isEmpty()) continue;
                    String url = purl.startsWith("http") ? purl : sip + purl;
                    if (url.startsWith("https://")) url = "http://" + url.substring(8);
                    LogMgr.d(TAG, "resolved src=qq q=" + q[2] + " in " + ms + "ms: " + c.name
                            + " - " + c.artist);
                    return url;
                }
            }
            LogMgr.d(TAG, "qq vkey: no playable quality (no rights?) in " + ms + "ms: " + c.name);
            return null;
        } catch (Throwable t) {
            LogMgr.d(TAG, "qq vkey error: " + t.getClass().getSimpleName() + " in "
                    + (System.currentTimeMillis() - t0) + "ms");
            return null;
        }
    }

    // ------------------------------------------------------------------ HTTP
    /** POST musicu.fcg {comm, req_0}; 返回 req_0 对象或 null。cr 非空时带登录 Cookie。 */
    private JSONObject call(JSONObject comm, String module, String method, JSONObject param,
                            JSONObject cr) throws Exception {
        JSONObject req = new JSONObject();
        req.put("module", module);
        req.put("method", method);
        req.put("param", param);
        JSONObject body = new JSONObject();
        body.put("comm", comm);
        body.put("req_0", req);
        String cookie = null;
        if (cr != null) {
            String id = musicid(cr);
            String key = cr.optString("musickey");
            cookie = "uin=" + id + "; qqmusic_uin=" + id + "; qqmusic_key=" + key + "; qm_keyst=" + key
                    + "; tmeLoginType=" + loginType(cr);
        }
        String resp = httpPost(MUSICU, body.toString(), cookie);
        if (resp == null) return null;
        JSONObject j = new JSONObject(resp);
        JSONObject r = j.optJSONObject("req_0");
        if (r == null) {
            r = new JSONObject();
            r.put("code", j.optInt("code", -1));
        }
        return r;
    }

    // v73 连接复用: Android 5.1 的 HttpURLConnection(OkHttp)只有把响应体读到 EOF 并 close 流, 连接才回池;
    // disconnect() 会直接关掉 socket。所以正常/错误响应都读完关闭, 只有异常或响应体超限没读完时才 disconnect。
    private static String httpPost(String urlStr, String body, String cookie) {
        HttpURLConnection conn = null;
        boolean reusable = false;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            conn.setRequestProperty("Referer", "https://y.qq.com/");
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            int code = conn.getResponseCode();
            if (code != 200) {
                LogMgr.d(TAG, "qq http " + code);
                reusable = drainClose(conn.getErrorStream());
                return null;
            }
            boolean[] eof = new boolean[1];
            String r = readHttp(conn.getInputStream(), eof);
            reusable = eof[0];
            return r;
        } catch (Exception e) {
            LogMgr.d(TAG, "qq http error: " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (conn != null && !reusable) conn.disconnect();
        }
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        boolean reusable = false;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://y.qq.com/");
            if (conn.getResponseCode() != 200) {
                reusable = drainClose(conn.getErrorStream());
                return null;
            }
            boolean[] eof = new boolean[1];
            String r = readHttp(conn.getInputStream(), eof);
            reusable = eof[0];
            return r;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null && !reusable) conn.disconnect();
        }
    }

    /** 读 HTTP 响应体(上限 2MB)并关闭流; eof[0]=true 表示读到了 EOF(连接可回池复用)。 */
    private static String readHttp(InputStream in, boolean[] eof) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        boolean end = false;
        try {
            while (true) {
                n = in.read(buf);
                if (n < 0) {
                    end = true;
                    break;
                }
                bos.write(buf, 0, n);
                if (bos.size() > 2 * 1024 * 1024) break;   // 超限: 不读了, 连接不复用
            }
        } finally {
            in.close();
        }
        eof[0] = end;
        return bos.toString("UTF-8");
    }

    /** 错误流读到 EOF 并关闭(最多 64KB); 读完返回 true(连接可复用)。没有错误流也算可复用。 */
    private static boolean drainClose(InputStream es) {
        if (es == null) return true;
        byte[] buf = new byte[4096];
        int total = 0;
        try {
            int n;
            while ((n = es.read(buf)) >= 0) {
                total += n;
                if (total > 64 * 1024) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                es.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > 2 * 1024 * 1024) break;
        }
        in.close();
        return bos.toString("UTF-8");
    }

    private static String readText(File f) throws Exception {
        return readAll(new FileInputStream(f));
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }
}
