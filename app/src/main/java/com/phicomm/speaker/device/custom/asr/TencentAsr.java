package com.phicomm.speaker.device.custom.asr;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.unisound.vui.util.LogMgr;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * v75: 唤醒后的语音识别改用腾讯云实时语音识别(WebSocket, wss://asr.cloud.tencent.com/asr/v2/&lt;appid&gt;)。
 *
 * <p>音频来源: 不自己开 AudioRecord(麦克风由云知声 Uni4micHal 独占)。用动态代理替换
 * SpeechUnderstander(com.unisound.sdk.bg) 的私有字段 aF(com.unisound.sdk.ad, 原实现 bk 的
 * a(boolean,byte[],int,int) 是空方法), 云知声在线识别每次 start 都会 ao.a(ctx, aF) 把它装到
 * com.unisound.sdk.ao.i 上; ao.b(...) 把同一份 VAD 后的音频一边交给云知声网络线程, 一边回调
 * ad.a(...)。所以拿到的就是云知声云端拿到的那份 16k/16bit/单声道 PCM(真机日志: 5s 语音
 * bufferLength=160800 = 32000 B/s)。云知声识别照常进行, 作为回退。
 *
 * <p>配置: app files/tencent_asr.json(appid / secret_id / secret_key / engine ...),
 * 由 tools/tencent_asr_push.py 写入。文件不存在 → 什么都不做(不装代理、不连腾讯), 完全原流程。
 * 日志只打长度/耗时/错误码/识别文字, 绝不打密钥和签名。
 */
public final class TencentAsr {
    private static final String TAG = "TencentASR";
    private static final String CFG_FILE = "tencent_asr.json";
    private static final String HOST = "asr.cloud.tencent.com";
    /** 默认引擎: 大模型2.0 中英粤+方言(含潮汕话)。免费额度不覆盖, 见 night-research.md 计费结论 */
    public static final String DEFAULT_ENGINE = "16k_zh_en_2.0";
    /** 200ms 一包: 16k * 16bit * 单声道 * 0.2s */
    private static final int PKT = 6400;
    private static final int BYTES_PER_MS = 32;
    /** 发送最多领先实时 1.5s(文档: 1 秒内最多 3 秒音频, 超过 1:1 可能出错) */
    private static final long MAX_LEAD_MS = 1500;
    /** 单次识别音频上限 60s */
    private static final int MAX_AUDIO = 60 * 1000 * BYTES_PER_MS;
    /** 会话开始前 400ms 内到达的音频(事件晚于录音时)并入会话 */
    private static final long ORPHAN_KEEP_MS = 400;
    /** 鉴权/开通/欠费/资源包耗尽这类持续性错误: 暂停使用 30 分钟, 期间直接走云知声 */
    private static final long DISABLE_MS = 30 * 60 * 1000L;
    /** vad_silence_time 只有这些引擎支持(文档 48982); 其余引擎不带该参数 */
    private static final String[] VAD_SILENCE_ENGINES = {
            "8k_zh", "8k_zh_finance", "16k_zh", "16k_zh_en", "8k_zh_large", "16k_en", "16k_en_large"
    };
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final TencentAsr INSTANCE = new TencentAsr();

    public static TencentAsr get() {
        return INSTANCE;
    }

    /** 结果回调, 一定在主线程、一定只回调一次。text==null 表示没有可用结果(reason 说明原因) */
    public interface ResultCallback {
        void onResult(String text, String reason);
    }

    static final class Config {
        String appid;
        String secretId;
        String secretKey;
        String engine = DEFAULT_ENGINE;
        int timeoutMs = 4000;
        int vadSilenceMs = 500;
        int filterModal = 1;
        boolean keepNative = true;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom rnd = new SecureRandom();
    private File filesDir;
    private Config cfg;
    private long cfgMtime = -1;
    private long cfgLen = -1;
    private volatile long disabledUntil = 0;
    private volatile String disabledWhy = null;
    /** 服务器时间 - 本机时间(由握手响应 Date 头校准; 音箱刚开机可能还是 2018 年) */
    private volatile long clockOffsetMs = 0;

    private volatile boolean tapInstalled = false;
    private volatile String tapError = null;
    private int sessionSeq = 0;
    private volatile Session current;

    // 会话开始之前到达的音频(最多 ORPHAN_KEEP_MS)
    private final List<byte[]> orphan = new ArrayList<byte[]>();
    private final List<Long> orphanAt = new ArrayList<Long>();

    private TencentAsr() {
    }

    // ------------------------------------------------------------------ config

    /** 读配置(文件变化自动重载)。没有文件/缺字段 → null(走原云知声流程) */
    private synchronized Config config(File dir) {
        if (dir != null) filesDir = dir;
        if (filesDir == null) return null;
        File f = new File(filesDir, CFG_FILE);
        if (!f.exists()) {
            if (cfg != null) LogMgr.d(TAG, "config removed -> unisound only");
            cfg = null;
            cfgMtime = -1;
            cfgLen = -1;
            return null;
        }
        long m = f.lastModified();
        long len = f.length();
        if (m == cfgMtime && len == cfgLen) return cfg;
        cfgMtime = m;
        cfgLen = len;
        cfg = null;
        disabledUntil = 0;   // 换了配置(新密钥/新引擎)立即重新尝试
        disabledWhy = null;
        try {
            byte[] b = readAll(f);
            JSONObject o = new JSONObject(new String(b, UTF8));
            Config c = new Config();
            c.appid = o.optString("appid", "").trim();
            c.secretId = o.optString("secret_id", "").trim();
            c.secretKey = o.optString("secret_key", "").trim();
            String e = o.optString("engine", "").trim();
            if (!e.isEmpty()) c.engine = e;
            c.timeoutMs = clamp(o.optInt("timeout_ms", c.timeoutMs), 1000, 10000);
            c.vadSilenceMs = clamp(o.optInt("vad_silence_ms", c.vadSilenceMs), 500, 2000);
            c.filterModal = clamp(o.optInt("filter_modal", c.filterModal), 0, 2);
            c.keepNative = o.optBoolean("keep_native", c.keepNative);
            if (!o.optBoolean("enabled", true)) {
                LogMgr.d(TAG, "config: enabled=false -> unisound only");
                return null;
            }
            if (c.appid.isEmpty() || c.secretId.isEmpty() || c.secretKey.isEmpty()) {
                LogMgr.e(TAG, "config: appid/secret_id/secret_key missing -> unisound only");
                return null;
            }
            cfg = c;
            LogMgr.d(TAG, "config loaded: engine=" + c.engine + " timeout=" + c.timeoutMs
                    + "ms vad_silence=" + (supportsVadSilence(c.engine) ? c.vadSilenceMs + "ms" : "n/a")
                    + " keep_native=" + c.keepNative + " (appid len " + c.appid.length()
                    + ", id len " + c.secretId.length() + ", key len " + c.secretKey.length() + ")");
            return cfg;
        } catch (Throwable t) {
            LogMgr.e(TAG, "config unreadable (" + t.getClass().getSimpleName() + ") -> unisound only");
            return null;
        }
    }

    /** 是否保留云知声原生技能(天气/闹钟/设置等)的结果。没有配置时返回 true */
    public boolean keepNative() {
        Config c = cfg;
        return c == null || c.keepNative;
    }

    private static boolean supportsVadSilence(String engine) {
        for (String e : VAD_SILENCE_ENGINES) if (e.equals(engine)) return true;
        return false;
    }

    // ------------------------------------------------------------------ PCM tap

    /**
     * 在云知声 SpeechUnderstander 上装 PCM 代理(幂等)。engine = ctx.engine()。
     * 失败只打一次日志, 之后永远走原流程。
     */
    private synchronized boolean ensureTap(Object engine) {
        if (tapInstalled) return true;
        if (tapError != null) return false;
        try {
            Class<?> suCls = Class.forName("com.unisound.client.SpeechUnderstander");
            Object su = findByType(engine, suCls, 4);
            if (su == null) throw new IllegalStateException("SpeechUnderstander not found");
            final Class<?> adCls = Class.forName("com.unisound.sdk.ad");
            Class<?> aoCls = Class.forName("com.unisound.sdk.ao");
            Field fAd = fieldOfType(su.getClass(), adCls);
            if (fAd == null) throw new IllegalStateException("bg.aF (ad) field not found");
            Object orig = fAd.get(su);
            if (orig == null) throw new IllegalStateException("bg.aF is null");
            if (Proxy.isProxyClass(orig.getClass()) && Proxy.getInvocationHandler(orig) instanceof Tap) {
                tapInstalled = true;
                return true;
            }
            Object proxy = Proxy.newProxyInstance(adCls.getClassLoader(), new Class<?>[]{adCls}, new Tap(orig));
            fAd.set(su, proxy);
            // 本次识别已经 ao.a(ctx, aF) 过了: 当前 ao.i 也换掉(以后每次 start 会自动装上 proxy)
            Field fAo = fieldOfType(su.getClass(), aoCls);
            Object ao = fAo == null ? null : fAo.get(su);
            if (ao != null) {
                Field fi = fieldOfType(aoCls, adCls);
                if (fi != null && fi.get(ao) == orig) fi.set(ao, proxy);
            }
            tapInstalled = true;
            LogMgr.d(TAG, "pcm tap installed (unisound online-ASR audio, 16k/16bit/mono)");
            return true;
        } catch (Throwable t) {
            tapError = t.getClass().getSimpleName() + ": " + t.getMessage();
            LogMgr.e(TAG, "pcm tap failed (" + tapError + ") -> unisound only");
            return false;
        }
    }

    private final class Tap implements InvocationHandler {
        private final Object orig;

        Tap(Object orig) {
            this.orig = orig;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            if (args != null && args.length == 4 && "a".equals(m.getName())
                    && args[0] instanceof Boolean && args[1] instanceof byte[]) {
                try {
                    onPcm((Boolean) args[0], (byte[]) args[1]);
                } catch (Throwable ignored) {
                    // 绝不影响云知声
                }
            }
            try {
                return m.invoke(orig, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
    }

    private static Field fieldOfType(Class<?> c, Class<?> type) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (f.getType() == type && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    /** 在 root 的实例字段里(广度, 最多 depth 层)找第一个 type 的实例 */
    private static Object findByType(Object root, Class<?> type, int depth) {
        if (root == null) return null;
        if (type.isInstance(root)) return root;
        List<Object> level = new ArrayList<Object>();
        level.add(root);
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<Object, Boolean>();
        seen.put(root, Boolean.TRUE);
        for (int d = 0; d < depth && !level.isEmpty(); d++) {
            List<Object> next = new ArrayList<Object>();
            for (Object o : level) {
                for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                    String n = k.getName();
                    if (n.startsWith("java.") || n.startsWith("android.")) break;
                    for (Field f : k.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        Class<?> ft = f.getType();
                        if (ft.isPrimitive() || ft.isArray() || ft == String.class) continue;
                        try {
                            f.setAccessible(true);
                            Object v = f.get(o);
                            if (v == null || seen.containsKey(v)) continue;
                            if (type.isInstance(v)) return v;
                            seen.put(v, Boolean.TRUE);
                            String vn = v.getClass().getName();
                            if (vn.startsWith("com.unisound.") || vn.startsWith("com.phicomm.")) next.add(v);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            level = next;
        }
        return null;
    }

    /** VAD 线程回调(云知声在线识别的音频)。只要 enabled=true 的数据, 单字节标记包(99/100)跳过 */
    private void onPcm(boolean speech, byte[] data) {
        if (!speech || data == null || data.length <= 1) return;
        byte[] copy = data.clone();
        Session s = current;
        if (s != null && !s.closed) {
            s.feed(copy);
            return;
        }
        synchronized (orphan) {
            long now = System.currentTimeMillis();
            orphan.add(copy);
            orphanAt.add(now);
            while (!orphanAt.isEmpty() && now - orphanAt.get(0) > ORPHAN_KEEP_MS) {
                orphan.remove(0);
                orphanAt.remove(0);
            }
        }
    }

    // ------------------------------------------------------------------ session API (主线程调用)

    /**
     * 云知声开始 ASR 录音(只在 engine.isASR() 时调用)。有配置且代理装好 → 开一路腾讯识别, 返回会话 id;
     * 否则返回 0(本轮完全走云知声)。
     */
    public int beginSession(File filesDir, Object engine) {
        Session old = current;
        current = null;
        if (old != null) old.close("new recording");
        Config c = config(filesDir);
        if (c == null) return 0;
        long now = System.currentTimeMillis();
        if (now < disabledUntil) {
            return 0;
        }
        if (!ensureTap(engine)) return 0;
        Session s;
        synchronized (this) {
            s = new Session(++sessionSeq, c);
        }
        synchronized (orphan) {
            for (int i = 0; i < orphan.size(); i++) {
                if (now - orphanAt.get(i) <= ORPHAN_KEEP_MS) s.feed(orphan.get(i));
            }
            orphan.clear();
            orphanAt.clear();
        }
        current = s;
        Thread t = new Thread(s, "TencentASR-" + s.id);
        t.setDaemon(true);
        t.start();
        LogMgr.d(TAG, "session " + s.id + " start engine=" + c.engine);
        return s.id;
    }

    /** 云知声 VAD 说完 / 停止录音: 发完剩余音频后发 {"type":"end"} */
    public void endAudio(String why) {
        Session s = current;
        if (s != null) s.endAudio(why);
    }

    /** 本轮已分发(或被取消): 关掉会话, 不再给腾讯发音频(省额度) */
    public void finishRound(String why) {
        Session s = current;
        current = null;
        if (s != null) s.close(why);
    }

    /** 当前进行中的会话 id(0 = 没有, 本轮不等腾讯) */
    public int activeSessionId() {
        Session s = current;
        return s == null || s.closed ? 0 : s.id;
    }

    /**
     * 等会话 sid 的最终结果。最多等到「说完(endAudio) + timeout_ms」; 已经过了就立即用手上的结果。
     * 到点没有 final: 有稳态句子就用稳态句子, 否则 text=null(回退云知声)。
     */
    public void awaitResult(int sid, final ResultCallback cb) {
        final Session s = current;
        if (s == null || s.id != sid) {
            post(cb, null, "session gone");
            return;
        }
        s.addWaiter(cb);
    }

    private void post(final ResultCallback cb, final String text, final String reason) {
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onResult(text, reason);
            }
        });
    }

    private void disable(String why) {
        disabledUntil = System.currentTimeMillis() + DISABLE_MS;
        disabledWhy = why;
        LogMgr.e(TAG, "disabled for 30 min (" + why + "), using unisound; push a new tencent_asr.json to retry now");
    }

    // ------------------------------------------------------------------ session

    private final class Session implements Runnable {
        final int id;
        final Config c;
        final long startAt = System.currentTimeMillis();
        private final Object lock = new Object();
        private byte[] buf = new byte[PKT * 8];
        private int total = 0;
        private boolean audioEnded = false;
        volatile long endAt = 0;
        volatile boolean closed = false;

        // 结果
        private final TreeMap<Integer, String> stable = new TreeMap<Integer, String>();
        private int partialIdx = -1;
        private String partial = null;
        private boolean done = false;      // final 或失败
        private String finalText = null;
        private String failReason = null;
        private long firstPartialAt = 0;
        private final List<ResultCallback> waiters = new ArrayList<ResultCallback>();

        private volatile Socket sock;
        private OutputStream out;
        private final Object writeLock = new Object();

        Session(int id, Config c) {
            this.id = id;
            this.c = c;
        }

        void feed(byte[] d) {
            synchronized (lock) {
                if (audioEnded || closed) return;
                if (total + d.length > MAX_AUDIO) return;
                if (total + d.length > buf.length) {
                    int n = Math.max(buf.length * 2, total + d.length);
                    byte[] nb = new byte[n];
                    System.arraycopy(buf, 0, nb, 0, total);
                    buf = nb;
                }
                System.arraycopy(d, 0, buf, total, d.length);
                total += d.length;
                lock.notifyAll();
            }
        }

        void endAudio(String why) {
            synchronized (lock) {
                if (audioEnded) return;
                audioEnded = true;
                endAt = System.currentTimeMillis();
                lock.notifyAll();
            }
            LogMgr.d(TAG, "session " + id + " audio end (" + why + ") audio=" + (total / BYTES_PER_MS)
                    + "ms at +" + (endAt - startAt) + "ms");
        }

        void close(String why) {
            if (closed) return;
            closed = true;
            synchronized (lock) {
                lock.notifyAll();
            }
            Socket s = sock;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            fail("closed: " + why, false);
        }

        // ---- 结果/等待

        void addWaiter(final ResultCallback cb) {
            long now = System.currentTimeMillis();
            synchronized (lock) {
                if (done) {
                    post(cb, finalText, finalText != null ? "final" : failReason);
                    return;
                }
                waiters.add(cb);
            }
            long base = endAt > 0 ? endAt : now;
            long wait = Math.max(0, base + c.timeoutMs - now);
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    String text;
                    boolean mine;
                    synchronized (lock) {
                        mine = waiters.remove(cb);
                        text = done ? finalText : stableText();
                    }
                    if (!mine) return;
                    if (text != null) {
                        LogMgr.d(TAG, "session " + id + " no final within " + c.timeoutMs
                                + "ms after end, use stable sentences len=" + text.length());
                        cb.onResult(text, "timeout(stable)");
                    } else {
                        cb.onResult(null, "timeout: no result " + c.timeoutMs + "ms after end");
                    }
                }
            }, wait);
        }

        private String stableText() {
            if (stable.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (String v : stable.values()) sb.append(v);
            String t = sb.toString().trim();
            return t.isEmpty() ? null : t;
        }

        private String allText() {
            StringBuilder sb = new StringBuilder();
            int maxIdx = -1;
            for (Map.Entry<Integer, String> e : stable.entrySet()) {
                sb.append(e.getValue());
                maxIdx = e.getKey();
            }
            if (partial != null && partialIdx > maxIdx) sb.append(partial);
            return sb.toString().trim();
        }

        private void finish(String text) {
            List<ResultCallback> ws;
            synchronized (lock) {
                if (done) return;
                done = true;
                finalText = (text == null || text.isEmpty()) ? null : text;
                if (finalText == null) failReason = "final: empty text";
                ws = new ArrayList<ResultCallback>(waiters);
                waiters.clear();
            }
            for (ResultCallback cb : ws) post(cb, finalText, finalText != null ? "final" : failReason);
        }

        private void fail(String reason, boolean log) {
            List<ResultCallback> ws;
            synchronized (lock) {
                if (done) return;
                done = true;
                failReason = reason;
                ws = new ArrayList<ResultCallback>(waiters);
                waiters.clear();
            }
            if (log) LogMgr.e(TAG, "session " + id + " failed: " + reason);
            for (ResultCallback cb : ws) {
                // 失败时如果手上已有稳态句子, 仍可用
                String st;
                synchronized (lock) {
                    st = stableText();
                }
                post(cb, st, st != null ? "failed(" + reason + "), use stable" : reason);
            }
        }

        // ---- 网络线程

        @Override
        public void run() {
            try {
                long t0 = System.currentTimeMillis();
                int code = connect();
                if (code == 4002 && clockSkewFixed) {
                    // 本机时钟不对(签名过期): 已用服务器 Date 校准, 重签一次(音频都在缓冲里, 不丢)
                    LogMgr.d(TAG, "session " + id + " retry with server clock");
                    code = connect();
                }
                if (code != 0) {
                    if (code == 4002 || code == 4003 || code == 4004 || code == 4005 || code == 6001) {
                        disable("code=" + code + " " + lastMsg);
                    }
                    fail("handshake code=" + code + " " + lastMsg, true);
                    return;
                }
                LogMgr.d(TAG, "session " + id + " connected in " + (System.currentTimeMillis() - t0) + "ms");
                Thread reader = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        readLoop();
                    }
                }, "TencentASR-rx-" + id);
                reader.setDaemon(true);
                reader.start();
                sendLoop();
            } catch (Throwable t) {
                if (!closed) fail("network: " + t.getClass().getSimpleName() + " " + t.getMessage(), true);
                closeSock();
            }
        }

        private String lastMsg = "";
        private boolean clockSkewFixed = false;

        /** TLS + WS 握手 + 等服务端确认。返回 code(0=成功; -1=网络/HTTP 失败) */
        private int connect() throws IOException {
            closeSock();
            long ts = (System.currentTimeMillis() + clockOffsetMs) / 1000L;
            TreeMap<String, String> p = new TreeMap<String, String>();
            p.put("engine_model_type", c.engine);
            p.put("expired", String.valueOf(ts + 24 * 3600));
            p.put("needvad", "1");
            p.put("nonce", String.valueOf(1 + rnd.nextInt(999999999)));
            p.put("secretid", c.secretId);
            p.put("timestamp", String.valueOf(ts));
            p.put("voice_format", "1");
            p.put("voice_id", UUID.randomUUID().toString());
            if (c.filterModal > 0) p.put("filter_modal", String.valueOf(c.filterModal));
            if (supportsVadSilence(c.engine)) p.put("vad_silence_time", String.valueOf(c.vadSilenceMs));
            StringBuilder q = new StringBuilder();
            for (Map.Entry<String, String> e : p.entrySet()) {
                if (q.length() > 0) q.append('&');
                q.append(e.getKey()).append('=').append(e.getValue());
            }
            String path = "/asr/v2/" + c.appid + "?" + q;
            String sig = sign(HOST + path, c.secretKey);
            path = path + "&signature=" + URLEncoder.encode(sig, "UTF-8");

            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(HOST, 443), 4000);
            plain.setSoTimeout(4000);
            SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(plain, HOST, 443, true);
            sock = s;
            if (closed) {
                closeSock();
                throw new IOException("closed");
            }
            s.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, s.getSession())) {
                throw new IOException("TLS hostname mismatch");
            }
            out = s.getOutputStream();
            InputStream in = s.getInputStream();
            byte[] key = new byte[16];
            rnd.nextBytes(key);
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + HOST + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + Base64.encodeToString(key, Base64.NO_WRAP) + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            synchronized (writeLock) {
                out.write(req.getBytes(UTF8));
                out.flush();
            }
            String headers = readHeaders(in);
            learnClock(headers);
            int status = parseStatus(headers);
            if (status != 101) {
                lastMsg = "HTTP " + status;
                return -1;
            }
            this.in = in;
            String first = recvText();
            JSONObject o;
            try {
                o = new JSONObject(first);
            } catch (Exception e) {
                lastMsg = "bad handshake reply";
                return -1;
            }
            int code = o.optInt("code", -1);
            lastMsg = o.optString("message", "");
            if (code == 4002 && !clockSkewFixed && clockFixedThisTime) {
                clockSkewFixed = true;   // 触发一次重签
            }
            return code;
        }

        private InputStream in;
        private boolean clockFixedThisTime = false;

        private void learnClock(String headers) {
            clockFixedThisTime = false;
            for (String line : headers.split("\r\n")) {
                if (line.regionMatches(true, 0, "Date:", 0, 5)) {
                    try {
                        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                        f.setTimeZone(TimeZone.getTimeZone("GMT"));
                        long server = f.parse(line.substring(5).trim()).getTime();
                        long off = server - System.currentTimeMillis();
                        if (Math.abs(off - clockOffsetMs) > 60_000) {
                            LogMgr.d(TAG, "clock skew " + (off / 1000) + "s vs server, signing with server time");
                            clockOffsetMs = off;
                            clockFixedThisTime = true;
                        }
                    } catch (Throwable ignored) {
                    }
                    return;
                }
            }
        }

        private void sendLoop() throws IOException, InterruptedException {
            int sent = 0;
            long firstSendAt = 0;
            while (!closed) {
                int avail;
                boolean ended;
                synchronized (lock) {
                    while (!closed && total - sent < PKT && !audioEnded) {
                        lock.wait(200);
                    }
                    avail = total - sent;
                    ended = audioEnded;
                }
                if (closed) return;
                if (avail > 0 && (avail >= PKT || ended)) {
                    int n = Math.min(PKT, avail);
                    long now = System.currentTimeMillis();
                    if (firstSendAt == 0) firstSendAt = now;
                    long lead = sent / BYTES_PER_MS - (now - firstSendAt);
                    if (lead > MAX_LEAD_MS) Thread.sleep(lead - MAX_LEAD_MS);
                    byte[] pkt = new byte[n];
                    synchronized (lock) {
                        System.arraycopy(buf, sent, pkt, 0, n);
                    }
                    wsSend(0x2, pkt);
                    sent += n;
                    continue;
                }
                if (ended && avail == 0) {
                    wsSend(0x1, "{\"type\":\"end\"}".getBytes(UTF8));
                    return;
                }
            }
        }

        private void readLoop() {
            try {
                sock.setSoTimeout(20_000);
                while (!closed) {
                    String msg = recvText();
                    if (msg == null) continue;
                    JSONObject o = new JSONObject(msg);
                    int code = o.optInt("code", 0);
                    if (code != 0) {
                        String m = o.optString("message", "");
                        if (code == 4003 || code == 4004 || code == 4005) disable("code=" + code + " " + m);
                        fail("code=" + code + " " + m, true);
                        break;
                    }
                    parseResult(o);
                    if (o.optInt("final", 0) == 1) {
                        String text;
                        synchronized (lock) {
                            text = allText();
                        }
                        long now = System.currentTimeMillis();
                        LogMgr.d(TAG, "session " + id + " final in "
                                + (endAt > 0 ? (now - endAt) + "ms after end" : "?ms (no end)")
                                + ", " + (now - startAt) + "ms since recording start, audio="
                                + (total / BYTES_PER_MS) + "ms, len=" + text.length() + " text=" + text);
                        finish(text);
                        break;
                    }
                }
            } catch (Throwable t) {
                if (!closed) {
                    fail("network: " + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : " " + t.getMessage()), true);
                }
            } finally {
                closeSock();
            }
        }

        /** 兼容两种返回: V1 result{slice_type,index,voice_text_str}; V2 sentences{sentence,sentence_type,sentence_id} */
        private void parseResult(JSONObject o) {
            JSONObject r = o.optJSONObject("result");
            if (r != null) {
                String t = r.optString("voice_text_str", "");
                int idx = r.optInt("index", 0);
                int st = r.optInt("slice_type", 1);
                onText(idx, t, st == 2);
            }
            Object ss = o.opt("sentences");
            if (ss instanceof JSONObject) {
                onSentence((JSONObject) ss);
            } else if (ss instanceof JSONArray) {
                JSONArray a = (JSONArray) ss;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject x = a.optJSONObject(i);
                    if (x != null) onSentence(x);
                }
            }
        }

        private void onSentence(JSONObject x) {
            String t = x.optString("sentence", "");
            if (t.isEmpty()) t = x.optString("text", "");
            if (t.isEmpty()) t = x.optString("voice_text_str", "");
            int idx = x.optInt("sentence_id", x.optInt("index", 0));
            boolean stableFlag = x.optInt("sentence_type", 0) == 1 || x.optInt("slice_type", 0) == 2;
            onText(idx, t, stableFlag);
        }

        private void onText(int idx, String t, boolean isStable) {
            if (t == null) return;
            t = t.trim();
            boolean first = false;
            synchronized (lock) {
                if (isStable) {
                    if (!t.isEmpty()) stable.put(idx, t); else stable.remove(idx);
                    if (idx == partialIdx) {
                        partial = null;
                        partialIdx = -1;
                    }
                } else if (!t.isEmpty()) {
                    partial = t;
                    partialIdx = idx;
                }
                if (!t.isEmpty() && firstPartialAt == 0) {
                    firstPartialAt = System.currentTimeMillis();
                    first = true;
                }
            }
            if (first) {
                LogMgr.d(TAG, "session " + id + " first partial in " + (firstPartialAt - startAt)
                        + "ms since recording start (len=" + t.length() + ")");
            }
        }

        // ---- WebSocket framing(客户端帧必须加掩码)

        private void wsSend(int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream h = new ByteArrayOutputStream(payload.length + 14);
            h.write(0x80 | opcode);
            int n = payload.length;
            if (n < 126) {
                h.write(0x80 | n);
            } else if (n < 65536) {
                h.write(0x80 | 126);
                h.write((n >>> 8) & 0xFF);
                h.write(n & 0xFF);
            } else {
                h.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) h.write((int) ((((long) n) >>> (8 * i)) & 0xFF));
            }
            byte[] mask = new byte[4];
            rnd.nextBytes(mask);
            h.write(mask, 0, 4);
            for (int i = 0; i < n; i++) h.write(payload[i] ^ mask[i & 3]);
            byte[] frame = h.toByteArray();
            synchronized (writeLock) {
                if (out == null) throw new IOException("not connected");
                out.write(frame);
                out.flush();
            }
        }

        /** 读一条文本/二进制消息(处理分片、ping); 服务器关闭 → EOFException */
        private String recvText() throws IOException {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            while (true) {
                int b0 = in.read();
                if (b0 < 0) throw new EOFException("connection closed");
                int b1 = readN(in, 1)[0] & 0xFF;
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) {
                    byte[] e = readN(in, 2);
                    len = ((e[0] & 0xFF) << 8) | (e[1] & 0xFF);
                } else if (len == 127) {
                    byte[] e = readN(in, 8);
                    len = 0;
                    for (byte b : e) len = (len << 8) | (b & 0xFF);
                }
                byte[] mk = (b1 & 0x80) != 0 ? readN(in, 4) : null;
                if (len > 4 * 1024 * 1024) throw new IOException("ws frame too large");
                byte[] data = readN(in, (int) len);
                if (mk != null) for (int k = 0; k < data.length; k++) data[k] ^= mk[k & 3];
                if (op == 0x8) throw new EOFException("server closed websocket");
                if (op == 0x9) {
                    wsSend(0xA, data);
                    continue;
                }
                if (op == 0xA) continue;
                msg.write(data, 0, data.length);
                if (fin) return new String(msg.toByteArray(), UTF8);
            }
        }

        private void closeSock() {
            Socket s = sock;
            sock = null;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    static String sign(String text, String key) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(UTF8), "HmacSHA1"));
            return Base64.encodeToString(mac.doFinal(text.getBytes(UTF8)), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IOException("sign failed: " + e.getClass().getSimpleName());
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < 16384) {
            int c = in.read();
            if (c < 0) throw new EOFException("eof in http headers");
            b.write(c);
            if ((state == 0 || state == 2) && c == '\r') state++;
            else if ((state == 1 || state == 3) && c == '\n') state++;
            else state = (c == '\r') ? 1 : 0;
            if (state == 4) break;
        }
        return new String(b.toByteArray(), UTF8);
    }

    private static int parseStatus(String headers) {
        try {
            String l = headers.substring(0, headers.indexOf("\r\n"));
            String[] p = l.split(" ");
            return Integer.parseInt(p[1].trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException("connection closed");
            off += r;
        }
        return b;
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int r;
            while ((r = in.read(tmp)) > 0) {
                b.write(tmp, 0, r);
                if (b.size() > 65536) throw new IOException("config too large");
            }
            return b.toByteArray();
        } finally {
            in.close();
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
