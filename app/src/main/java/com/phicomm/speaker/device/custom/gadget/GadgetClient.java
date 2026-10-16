package com.phicomm.speaker.device.custom.gadget;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Muse gadget protocol client for the Feixun R1.
 * Java port of the Python prototype (verified against
 * facebookincubator/muse-gadget-sdk, Apache 2.0).
 *
 * Flow: fetch_vms (SDK token) -> TLS hatch.metaaivm.com:443 ->
 * WS upgrade /v1/noise?vm_id=.. -> Noise XX handshake ->
 * POST /chat/subscribe (long-lived) + POST /chat/stream per turn.
 *
 * No BLE pairing: the protocol is purely token-authenticated.
 * Only java.net / javax.net.ssl / org.json (already in the app).
 */
public final class GadgetClient {
    private static final String TAG = "Gadget";
    private static void log(String m) {
        try { android.util.Log.d(TAG, m); } catch (Throwable t) { System.out.println(TAG + ": " + m); }
    }
    private static String preview(byte[] b) {
        if (b == null) return "null";
        String t = new String(b, 0, Math.min(b.length, 300), StandardCharsets.UTF_8);
        return t.replaceAll("[\\r\\n]+", " ") + (b.length > 300 ? "...(" + b.length + "B)" : "");
    }
    private final GadgetProto.Reassembler reasm = new GadgetProto.Reassembler();
    private int controlStream = -1;
    private int chatStream = -1;
    private static final String FETCH_HOST = "api.muse.ai";
    private static final String VM_HOST = "hatch.metaaivm.com";
    private static final int VM_PORT = 443;
    private static final String WS_KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    /** 整轮硬超时(原 60s): 到点还没有任何句子就放弃, 由上层播「网络有点慢」 */
    private static final long REPLY_TIMEOUT_MS = 40_000;
    /** 首字超时: 发问后这么久还没有任何文字就放弃(Muse 正常 6~9 秒出首字) */
    private static final long FIRST_TEXT_TIMEOUT_MS = 20_000;
    /** chat/stream 的 HTTP 响应这么久没到 = 连接半死, 重连重问一次 */
    private static final long HTTP_RESP_TIMEOUT_MS = 4_000;
    /** 已有文字且 text_append 静默这么久才算整段说完(不再用 250ms 截断, 长回答可能分多段) */
    private static final long TEXT_QUIET_MS = 3_000;
    /** 收到 message_done 后再等这么久, 看是否还有下一段消息; 剩余文字在 message_done 时立即切出 */
    private static final long MSG_DONE_GRACE_MS = 800;
    /** 发新问题前读掉上一轮提前返回后残留的事件, 读到连续这么久没新帧为止 */
    private static final long PRE_DRAIN_QUIET_MS = 150;
    /** 上一轮干净结束(message_done+grace 收尾、帧都读完)时, 发问前只做这么短的探测 */
    private static final long PRE_DRAIN_PROBE_MS = 30;
    /**
     * 上一轮是否干净结束: 走完 message_done + MSG_DONE_GRACE_MS、没有拼了一半的分片、订阅缓冲无残行。
     * 只在 doChat 正常收尾时置 true; 新一轮开始/连接关闭/任何异常路径都保持或置回 false。
     */
    private boolean lastTurnClean = false;
    private static final long KEEPALIVE_MS = 25_000;
    /** 保活重连退避上限(原 300s) */
    private static final long MAX_BACKOFF_MS = 60_000;
    /** 读循环每片最长等待: 决定打断/取消被发现的速度 */
    private static final int READ_SLICE_MS = 300;
    /** 帧读到一半后, 剩余字节的读超时(只允许在帧首字节之前超时) */
    private static final int MID_FRAME_TIMEOUT_MS = 10_000;

    /** 首轮随人设一起发的儿童规则(每条新连接第一轮发一次) */
    private static final String KID_RULES =
            "你是家里智能音箱里的小伙伴, 正在和一个小朋友说话。"
            + "回答用 3 句话以内, 口语化, 不用列表、符号、表情和英文缩写, 方便直接朗读。"
            + "只聊天和讲知识/故事, 不执行任何操作或工具, 不讲吓人、暴力、不适合儿童的内容。";
    /** 每一轮都带的短规则(Muse 会话被服务端重置时规则也不会丢) */
    static final String KID_RULE_SHORT =
            "(提示: 对小朋友说话, 3句以内, 口语, 不讲吓人或不适合儿童的内容)";
    private boolean personaSent = false;
    private Thread keepAliveThread;
    private volatile boolean keepAliveRunning = false;

    private final String sdkToken;          // legacy, only when creds == null
    private final DeviceCredentials creds;  // from Muse BLE pairing (preferred)
    private volatile Socket sock;
    private InputStream in;
    private OutputStream out;
    private NoiseXX noise;
    private int nextStream = 1;
    private int subStream;
    private final ByteArrayOutputStream subBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream ctrlBuf = new ByteArrayOutputStream();

    /** 建连(fetch_vms + TLS + WS + Noise)串行化, 但不占用对象锁 */
    private final Object connectLock = new Object();
    /** 每装上一条新连接 +1 */
    private int connGen = 0;
    /** 打断/新提问: +1, 正在读的流看到变化立即跳出并释放锁 */
    private volatile int cancelGen = 0;
    /** 网络恢复 / 收到断线信号: 保活线程立即重连 */
    private volatile boolean reconnectNow = false;
    /** 保活线程正在锁外建连: 对话线程先等它(wait 释放锁), 不重复建第二条 */
    private volatile boolean warmConnecting = false;
    private static final long WAIT_WARM_CONNECT_MS = 8_000;
    /** 保活: 最近一次收到任意帧 / 发 ping 的时间, 服务器是否回过 pong */
    private volatile long lastRxAt = 0;
    private volatile long lastPingAt = 0;
    private volatile boolean pongSeen = false;
    private int missedPongs = 0;
    /** 网络状态(CONNECTIVITY_CHANGE) */
    private volatile Boolean lastNetUp = null;
    private volatile String lastNetKey = null;

    /** 发问后首字/整轮超时(没有任何句子): 上层应播简短提示, 不再回落重问 */
    public static final class ReplyTimeout extends IOException {
        ReplyTimeout(String m) { super(m); }
    }

    public GadgetClient(String sdkToken) {
        this.sdkToken = sdkToken;
        this.creds = null;
    }

    public GadgetClient(DeviceCredentials creds) {
        this.sdkToken = null;
        this.creds = creds;
    }


    private String apiHost() {
        if (creds == null) return FETCH_HOST;
        String r = creds.apiRoot().substring("https://".length());
        int slash = r.indexOf('/');
        return slash >= 0 ? r.substring(0, slash) : r;
    }

    private String apiPrefix() {
        if (creds == null) return "";
        String r = creds.apiRoot().substring("https://".length());
        int slash = r.indexOf('/');
        return slash >= 0 ? r.substring(slash) : "";
    }

    private String vmHost() {
        return creds == null ? VM_HOST : creds.noiseHost();
    }

    private String bearer() {
        return creds == null ? sdkToken : creds.accessToken();
    }

    // ---------------- fetch_vms ----------------
    private static final class VmInfo {
        String vmId, vmName, vmToken;
    }

    private VmInfo fetchVms() throws IOException {
        try {
            if (creds != null) creds.refreshIfDue();
            try {
                return fetchVmsInner();
            } catch (TokenRejected e) {
                if (creds == null) throw e;
                creds.refresh();          // rotates + persists both tokens
                return fetchVmsInner();
            }
        } catch (JSONException e) {
            throw new IOException("fetch_vms: bad JSON: " + e.getMessage());
        }
    }

    private static final class TokenRejected extends IOException {
        TokenRejected(String m) { super(m); }
    }

    private VmInfo fetchVmsInner() throws IOException, JSONException {
        SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket s = (SSLSocket) f.createSocket(apiHost(), 443)) {
            s.setSoTimeout(15_000);
            String req = "GET " + apiPrefix() + "/fetch_vms HTTP/1.0\r\n"
                    + "Host: " + apiHost() + "\r\n"
                    + "Authorization: Bearer " + bearer() + "\r\n"
                    + "X-API-Version: 1.0.0\r\n"
                    + "Connection: close\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            byte[] head = readHeaders(s.getInputStream());
            int status = parseStatus(head);
            byte[] body = readAll(s.getInputStream());
            if (status == 401 || status == 403)
                throw new TokenRejected("token rejected (HTTP " + status + ")");
            if (status != 200) {
                log("fetch_vms HTTP " + status + " body=" + preview(body));
                throw new IOException("fetch_vms failed (HTTP " + status + ")");
            }
            JSONObject root = new JSONObject(new String(body, StandardCharsets.UTF_8));
            JSONArray list = root.optJSONArray("vm_list");
            if (list == null || list.length() == 0)
                throw new IOException("no VMs in fetch_vms response");
            JSONObject pick = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject e = list.getJSONObject(i);
                if (e.optString("vm_auth_token", "").length() > 0) {
                    if (pick == null) pick = e;
                    if (e.optBoolean("default", false)) { pick = e; break; }
                }
            }
            if (pick == null) throw new IOException("no usable VM");
            VmInfo vm = new VmInfo();
            vm.vmId = pick.optString("vm_id", "");
            if (vm.vmId.isEmpty())
                vm.vmId = idFromUrl(pick.optString("vm_ws_url",
                        pick.optString("vm_url", "")));
            vm.vmName = pick.optString("vm_name", "");
            vm.vmToken = pick.getString("vm_auth_token");
            if (vm.vmId.isEmpty() || vm.vmToken.isEmpty())
                throw new IOException("bad VM entry");
            log("fetch_vms OK: " + list.length() + " VM(s), using " + vm.vmName);
            return vm;
        }
    }

    private static String idFromUrl(String url) {
        if (url == null) return "";
        int p = url.indexOf("://");
        String host = p >= 0 ? url.substring(p + 3) : url;
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int dot = host.indexOf('.');
        return dot >= 0 ? host.substring(0, dot) : host;
    }

    // ---------------- connect ----------------
    /** 一条已完成 Noise 握手、还没装到客户端上的连接 */
    private static final class Conn {
        SSLSocket sock;
        InputStream in;
        OutputStream out;
        NoiseXX noise;
        void closeQuiet() {
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * 建一条新连接(fetch_vms -> TLS -> WS upgrade -> Noise XX)。只用局部变量,
     * 不碰当前连接, 也不需要对象锁; connectLock 只保证同一时刻只有一个线程在建连/刷新令牌。
     */
    private Conn openConn() throws Exception {
        synchronized (connectLock) {
            VmInfo vm = fetchVms();
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket s = (SSLSocket) f.createSocket(vmHost(), VM_PORT);
            try {
                s.setSoTimeout(15_000);
                Conn c = new Conn();
                c.sock = s;
                c.in = s.getInputStream();
                c.out = s.getOutputStream();
                String path = "/v1/noise?vm_id=" + URLEncoder.encode(vm.vmId, "UTF-8");
                String req = "GET " + path + " HTTP/1.1\r\n"
                        + "Host: " + vmHost() + "\r\n"
                        + "Authorization: Bearer " + vm.vmToken + "\r\n"
                        + "Upgrade: websocket\r\n"
                        + "Connection: Upgrade\r\n"
                        + "Sec-WebSocket-Key: " + WS_KEY + "\r\n"
                        + "Sec-WebSocket-Version: 13\r\n\r\n";
                c.out.write(req.getBytes(StandardCharsets.US_ASCII));
                c.out.flush();
                int status = parseStatus(readHeaders(c.in));
                if (status == 401 || status == 403)
                    throw new IOException("VM refused the token (HTTP " + status + ")");
                if (status != 101)
                    throw new IOException("WS upgrade failed (HTTP " + status + ")");
                log("WS upgraded to " + vmHost());
                NoiseXX nz = new NoiseXX();
                wsSend(c.out, nz.writeMessage1());
                nz.readMessage2(wsRecv(s, c.in, c.out));
                wsSend(c.out, nz.writeMessage3(new byte[0]));
                log("Noise XX handshake done");
                c.noise = nz;
                return c;
            } catch (Exception e) {
                try { s.close(); } catch (IOException ignored) { }
                throw e;
            }
        }
    }

    /** 持有对象锁调用: 换上新连接, 发 link.register + 打开 subscribe(只有几次发送, 很快)。 */
    private void install(Conn c) throws Exception {
        closeQuiet();
        sock = c.sock;
        in = c.in;
        out = c.out;
        noise = c.noise;
        nextStream = 1;
        subBuf.reset();
        ctrlBuf.reset();
        personaSent = false;
        connGen++;
        lastRxAt = System.currentTimeMillis();
        lastPingAt = 0;
        missedPongs = 0;
        try {
            if (creds != null) registerDevice();
            subStream = openStream("POST", "/chat/subscribe",
                    new String[]{"Content-Type", "application/json", "Accept", "application/x-ndjson",
                            "x-app-id", "musegadget"},
                    "{}".getBytes(StandardCharsets.UTF_8), true);
        } catch (Exception e) {
            closeQuiet();
            throw e;
        }
    }

    /** 对话线程用(已持有锁或首次): 同步建连并装上。 */
    public synchronized void connect() throws Exception {
        close();
        install(openConn());
    }

    /** Control stream + link.register, so chat turns are attributed to this paired device. */
    private void registerDevice() throws Exception {
        controlStream = openStream("POST", "/link-control",
                new String[]{"x-app-id", "musegadget"}, new byte[0], false);
        JSONObject params = new JSONObject()
                .put("node_id", creds.nodeId())
                .put("display_name", "Phicomm R1")
                .put("platform", "linux")
                .put("version", "0.1.0")
                .put("device_family", "homehub")
                .put("model_id", "linux")
                .put("is_wakeup_supported", false)
                .put("commands_v2", new JSONObject());
        sendControl(new JSONObject()
                .put("type", "req")
                .put("id", java.util.UUID.randomUUID().toString())
                .put("method", "link.register")
                .put("params", params));
        log("sent link.register as " + creds.nodeId());
    }

    /** 控制流消息: 4 字节小端长度前缀 + JSON。 */
    private void sendControl(JSONObject m) throws Exception {
        byte[] msg = m.toString().getBytes(StandardCharsets.UTF_8);
        byte[] framed = new byte[4 + msg.length];
        framed[0] = (byte) msg.length;
        framed[1] = (byte) (msg.length >>> 8);
        framed[2] = (byte) (msg.length >>> 16);
        framed[3] = (byte) (msg.length >>> 24);   // little-endian u32 length prefix
        System.arraycopy(msg, 0, framed, 4, msg.length);
        byte[] env = GadgetProto.encodeBodyChunkEnvelope(controlStream, framed, false);
        wsSend(noise.encrypt(GadgetProto.encodeTransportFrame(env)));
    }

    /**
     * 服务器在控制流上发来的数据(持有锁调用)。按 4 字节小端长度前缀拆消息(整段以 '{' 开头时按单条 JSON);
     * type=req(如 link.invoke)一律回 {type:res, ok:false, error:{code:unsupported}}, 本设备不执行任何命令。
     * 日志只打 type/method, 不打内容。
     */
    private void handleControl(GadgetProto.ServerFrame sf) {
        if (sf.isResponse) log("control: resp " + sf.status);
        if (sf.body == null || sf.body.length == 0) return;
        try {
            if (ctrlBuf.size() == 0 && sf.body[0] == '{') {
                onControlMessage(new String(sf.body, StandardCharsets.UTF_8));
                return;
            }
            ctrlBuf.write(sf.body, 0, sf.body.length);
            byte[] b = ctrlBuf.toByteArray();
            int p = 0;
            while (b.length - p >= 4) {
                long len = (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8) | ((b[p + 2] & 0xFFL) << 16)
                        | ((b[p + 3] & 0xFFL) << 24);
                if (len > (1 << 20)) {
                    log("control: bad frame length " + len + ", drop buffer");
                    ctrlBuf.reset();
                    return;
                }
                if (b.length - p - 4 < len) break;
                onControlMessage(new String(b, p + 4, (int) len, StandardCharsets.UTF_8));
                p += 4 + (int) len;
            }
            ctrlBuf.reset();
            if (p < b.length) ctrlBuf.write(b, p, b.length - p);
        } catch (Exception e) {
            log("control: handle error " + e.getClass().getSimpleName());
            ctrlBuf.reset();
        }
    }

    private void onControlMessage(String js) throws Exception {
        JSONObject m;
        try {
            m = new JSONObject(js);
        } catch (JSONException e) {
            log("control: non-JSON message (" + js.length() + " chars)");
            return;
        }
        String type = m.optString("type", "");
        String method = m.optString("method", "");
        log("control: " + type + (method.isEmpty() ? "" : " " + method)
                + (m.has("ok") ? " ok=" + m.optBoolean("ok") : ""));
        if ("req".equals(type) && controlStream > 0) {
            sendControl(new JSONObject()
                    .put("type", "res")
                    .put("id", m.opt("id") == null ? JSONObject.NULL : m.opt("id"))
                    .put("ok", false)
                    .put("error", new JSONObject()
                            .put("code", "unsupported")
                            .put("message", "not supported on this device")));
            log("control: replied unsupported to " + (method.isEmpty() ? "req" : method));
        }
    }

    private int openStream(String verb, String path, String[] headers,
                           byte[] body, boolean endBody) throws Exception {
        int sid = nextStream++;
        byte[] env = GadgetProto.encodeRequestEnvelope(sid, verb, path, headers, body, endBody);
        wsSend(noise.encrypt(GadgetProto.encodeTransportFrame(env)));
        return sid;
    }


    // ---------------- chat ----------------
    /**
     * One text turn. systemPrompt: persona prompt (may be null).
     * historyText: recent persona history, may be null (truncated to ~1500 chars).
     * Returns the assistant's reply text (may be empty on failure/timeout).
     */
    public String chat(String systemPrompt, String historyText,
                       String userInput) throws Exception {
        return chatStream(systemPrompt, historyText, userInput, null);
    }

    /** 按句流式回调。在 Gadget 工作线程(持有本对象锁)里调用, 实现要快、不要阻塞。 */
    public interface SentenceListener {
        void onSentence(String sentence);
    }

    /**
     * 打断/新提问时调用(任意线程, 不需要锁): 正在读的流在 READ_SLICE_MS 内跳出并释放锁。
     * 被打断的那轮服务端可能还在继续推文字, 跳出后会断开这条连接并在后台立即重连, 防止旧回答串到下一轮。
     */
    public void cancelCurrent() {
        cancelGen++;
    }

    private String buildMessage(boolean withPersona, String systemPrompt, String userInput) {
        StringBuilder sb = new StringBuilder();
        if (withPersona) {
            sb.append(KID_RULES).append("\n");
            if (systemPrompt != null && !systemPrompt.isEmpty())
                sb.append(systemPrompt).append("\n");
        }
        sb.append(KID_RULE_SHORT).append("\n小朋友说: ").append(userInput);
        return sb.toString();
    }

    /**
     * 流式版本: 增量文字里遇到句末标点(。！？!?；; 换行…), 或超过约 40 字遇到逗号, 就切出一句交给
     * listener; message_done 时把剩余文字作为一句切出, 再等 MSG_DONE_GRACE_MS 没有新消息即结束;
     * 文字静默 TEXT_QUIET_MS 也结束。返回整段文字(兜底/存历史用)。listener 可为 null(=整段接口)。
     * 方法返回即"流结束"。chat/stream 4 秒无响应 -> 重连重问一次; 首字 20s / 整轮 40s 没有任何句子
     * -> 抛 ReplyTimeout; cancelCurrent() -> 立即返回已有部分。
     */
    public synchronized String chatStream(String systemPrompt, String historyText,
                                          String userInput, SentenceListener listener) throws Exception {
        final int myGen = cancelGen;
        if ((sock == null || noise == null) && warmConnecting) {
            // 保活线程正在后台重连(打断/断网后): 等它装好, 别再并行建第二条连接
            long t0 = System.currentTimeMillis();
            while ((sock == null || noise == null) && warmConnecting
                    && System.currentTimeMillis() - t0 < WAIT_WARM_CONNECT_MS && cancelGen == myGen) {
                wait(200);
            }
            log("chat waited " + (System.currentTimeMillis() - t0) + " ms for background connect ("
                    + (sock != null ? "ready" : "not ready") + ")");
        }
        if (sock == null || noise == null) {
            connect();   // first turn / keep-alive 还没连上
        }
        if (cancelGen != myGen) return "";
        // Muse 服务端自己保存会话上下文, 不再每轮重发人设和历史(提示词越长它想得越久)。
        // 每条新连接的第一轮带完整儿童规则 + 人设; 之后每轮只带一句短规则。historyText 不再使用。
        boolean withPersona = !personaSent;
        JSONObject req = new JSONObject()
                .put("message", buildMessage(withPersona, systemPrompt, userInput))
                .put("output_modality", "text");
        if (creds != null) req.put("device_id", creds.nodeId());
        byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
        StreamState ss = new StreamState(listener);
        try {
            // 上一轮结束后连接上可能还挂着它的 message_done / 状态事件 / 后续消息;
            // 先读掉丢弃, 免得这一轮把上一轮的残余当成回复。
            drainPending();
            String r = doChat(body, ss, myGen);
            if (withPersona) personaSent = true;
            return r;
        } catch (ReplyTimeout e) {
            log("reply timeout (" + e.getMessage() + "), drop connection, reconnect in background");
            dropAndReconnectInBackground();
            throw e;
        } catch (Exception e) {
            if (ss.emitted > 0) {
                // 已经播出去的句子不能再重问一遍(会重复播报): 保留已有部分, 断开连接后台重连
                log("stream broken after " + ss.emitted + " sentence(s), keep partial: " + e);
                ss.flushRemainder();
                dropAndReconnectInBackground();
                if (withPersona) personaSent = true;
                return ss.all.toString().trim();
            }
            if (cancelGen != myGen) {
                log("chat failed after cancel, no retry: " + e);
                dropAndReconnectInBackground();
                return "";
            }
            log("chat failed, reconnect once: " + e);
            long t0 = System.currentTimeMillis();
            connect();           // one reconnect attempt (covers IO + protocol errors)
            log("chat reconnect done in " + (System.currentTimeMillis() - t0) + " ms, ask again");
            if (cancelGen != myGen) return "";
            // 新连接 = 新会话: 重新带上人设
            req.put("message", buildMessage(true, systemPrompt, userInput));
            body = req.toString().getBytes(StandardCharsets.UTF_8);
            try {
                String r = doChat(body, new StreamState(listener), myGen);
                personaSent = true;
                return r;
            } catch (ReplyTimeout te) {
                log("reply timeout after reconnect (" + te.getMessage() + ")");
                dropAndReconnectInBackground();
                throw te;
            }
        }
    }

    /** 持有锁调用: 关掉当前连接, 让保活线程马上在锁外重连。 */
    private void dropAndReconnectInBackground() {
        closeQuiet();
        reconnectNow = true;
        Thread t = keepAliveThread;
        if (t != null) t.interrupt();
    }


    // ---------------- sentence splitting ----------------
    /** 句末标点: 遇到就切 */
    private static final String SENT_END = "。！？!?；;…\n";
    /** 软切分: 超过这么多字遇到逗号就切 */
    private static final int SOFT_MAX = 40;
    /** 硬切分: 一直没有标点, 超过这么多字强制切 */
    private static final int HARD_MAX = 100;
    /** 句末标点前的有效字数少于这个就先不切, 和下一句合并(避免「好的！」单独一次 TTS) */
    private static final int MIN_SENT = 4;
    /** 跟在句末标点后面、应归到本句的收尾符号 */
    private static final String CLOSERS = "”’」』）)】\"'。！？!?…";

    /** 一轮流式的状态: 切句缓冲 + 计数 + 回调。 */
    private static final class StreamState {
        final SentenceListener listener;
        final StringBuilder buf = new StringBuilder();   // 还没切出去的文字
        final StringBuilder all = new StringBuilder();   // 本轮全部文字
        int emitted = 0;
        long startedAt = System.currentTimeMillis();

        StreamState(SentenceListener l) { listener = l; }

        void feed(String t) {
            if (t == null || t.isEmpty()) return;
            all.append(t);
            buf.append(t);
            int i = 0;
            while (i < buf.length()) {
                char c = buf.charAt(i);
                boolean cut = false;
                if (c == '\n') {
                    cut = true;
                } else if (SENT_END.indexOf(c) >= 0) {
                    cut = contentLen(buf, i) >= MIN_SENT;
                } else if ((c == '，' || c == ',') && i + 1 >= SOFT_MAX) {
                    cut = true;
                } else if (i + 1 >= HARD_MAX) {
                    cut = true;
                }
                if (!cut) { i++; continue; }
                int end = i + 1;
                if (c != '\n') {
                    while (end < buf.length() && CLOSERS.indexOf(buf.charAt(end)) >= 0) end++;
                }
                String sent = buf.substring(0, end);
                buf.delete(0, end);
                emit(sent);
                i = 0;
            }
        }

        /** 消息结束/流结束: 剩余文字作为一句切出 */
        void flushRemainder() {
            if (buf.length() == 0) return;
            String rest = buf.toString();
            buf.setLength(0);
            emit(rest);
        }

        /** 消息边界: 全文里用换行隔开两段消息 */
        void messageBoundary() {
            if (all.length() > 0 && all.charAt(all.length() - 1) != '\n') all.append('\n');
        }

        private void emit(String s) {
            String t = s.trim();
            if (t.isEmpty() || contentLen(t, t.length() - 1) == 0) return;   // 纯标点/空白: 跳过
            emitted++;
            long ms = System.currentTimeMillis() - startedAt;
            if (emitted == 1) log("stream: first sentence ready after " + ms + " ms (len=" + t.length() + ")");
            else log("stream: sentence #" + emitted + " len=" + t.length() + " at " + ms + " ms");
            if (listener != null) {
                try {
                    listener.onSentence(t);
                } catch (Throwable e) {
                    log("stream: listener error: " + e);
                }
            }
        }

        /** buf[0..last] 里字母/数字/汉字的个数 */
        private static int contentLen(CharSequence b, int last) {
            int n = 0;
            for (int k = 0; k <= last && k < b.length(); k++) {
                if (Character.isLetterOrDigit(b.charAt(k))) n++;
            }
            return n;
        }
    }

    private String doChat(byte[] body, StreamState ss, int myGen) throws Exception {
        chatStream = openStream("POST", "/chat/stream",
                new String[]{"Content-Type", "application/json",
                        "x-request-id", java.util.UUID.randomUUID().toString(),
                        "x-app-id", "musegadget"}, body, true);
        lastTurnClean = false;
        long startedAt = System.currentTimeMillis();
        ss.startedAt = startedAt;
        long deadline = startedAt + REPLY_TIMEOUT_MS;
        long httpDeadline = startedAt + HTTP_RESP_TIMEOUT_MS;
        long firstTextDeadline = startedAt + FIRST_TEXT_TIMEOUT_MS;
        boolean gotHttp = false;                   // chat/stream 的 HTTP 响应(或任何本轮事件)已到
        StringBuilder cur = new StringBuilder();   // 当前这条消息已收到的增量文字
        boolean sawDelta = false;                  // 本轮收到过 text_append(之后不再采用整条消息文字, 防重复)
        boolean inTurn = false;
        long quietUntil = 0;
        String endReason = "deadline " + REPLY_TIMEOUT_MS + "ms";

        while (true) {
            long now = System.currentTimeMillis();
            if (cancelGen != myGen) {
                endReason = "cancelled";
                log("stream cancelled after " + (now - startedAt) + " ms (sentences=" + ss.emitted
                        + "), release lock, reconnect in background");
                dropAndReconnectInBackground();   // 服务端可能还在推这一轮, 不能留在连接上串到下一轮
                break;
            }
            if (!gotHttp && now >= httpDeadline) {
                throw new IOException("chat/stream no response in " + HTTP_RESP_TIMEOUT_MS + " ms");
            }
            if (ss.all.length() == 0 && now >= firstTextDeadline) {
                if (ss.emitted == 0)
                    throw new ReplyTimeout("no text in " + FIRST_TEXT_TIMEOUT_MS + " ms");
                break;
            }
            // Hard deadline: always exit, never hang (method is synchronized).
            if (now >= deadline) {
                if (ss.emitted == 0 && ss.buf.toString().trim().isEmpty())
                    throw new ReplyTimeout("no sentence in " + REPLY_TIMEOUT_MS + " ms");
                break;
            }
            if (inTurn && quietUntil > 0 && now >= quietUntil) break;
            long wait = Math.min(READ_SLICE_MS, deadline - now);
            if (inTurn && quietUntil > 0) wait = Math.min(wait, quietUntil - now);
            sock.setSoTimeout((int) Math.max(50, wait));
            byte[] frame;
            try {
                frame = wsRecv();   // 只会在帧首字节之前超时; 帧读到一半不会丢字节
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            byte[] plain;
            try {
                plain = noise.decrypt(frame);
            } catch (Exception e) {
                log("decrypt failed: " + e);
                throw new IOException("noise decrypt failed", e);  // nonce out of sync -> reconnect
            }
            GadgetProto.ServerFrame sf;
            try {
                byte[] whole = reasm.feed(plain);
                if (whole == null) continue;          // more chunks pending
                sf = GadgetProto.decodeServiceResponse(whole);
            } catch (IllegalArgumentException e) {
                log("bad frame: " + e.getMessage());
                continue;
            }
            if (sf.streamId == controlStream) {
                handleControl(sf);
                continue;
            }
            if (sf.streamId == chatStream) {
                if (sf.isReset) throw new IOException("chat stream reset by server");
                if (sf.isResponse) {
                    gotHttp = true;
                    log("chat/stream HTTP " + sf.status + " after " + (System.currentTimeMillis() - startedAt)
                            + " ms " + preview(sf.body));
                    if (sf.status >= 400) throw new IOException("chat/stream HTTP " + sf.status);
                }
                continue;
            }
            if (sf.streamId != subStream) {
                log("frame for unknown stream " + sf.streamId);
                continue;
            }
            if (sf.isReset) throw new IOException("subscribe stream reset by server");
            if (sf.isResponse) {
                log("subscribe HTTP " + sf.status);
                if (sf.status >= 400) throw new IOException("subscribe HTTP " + sf.status);
            }
            subBuf.write(sf.body, 0, sf.body.length);
            byte[] buf = subBuf.toByteArray();
            int start = 0;
            for (int i = 0; i < buf.length; i++) {
                if (buf[i] == '\n') {
                    String line = new String(buf, start, i - start, StandardCharsets.UTF_8).trim();
                    start = i + 1;
                    if (line.isEmpty()) continue;
                    JSONObject ev;
                    try {
                        ev = new JSONObject(line);
                    } catch (Exception e) {
                        continue;
                    }
                    if (!"event".equals(ev.optString("type", ""))) continue;
                    String name = ev.optString("event", "");
                    JSONObject payload = ev.optJSONObject("payload");
                    if (payload == null) payload = new JSONObject();
                    log("event " + name);
                    if ("user".equals(payload.optString("role", ""))) {
                        gotHttp = true;   // 服务端已收到本轮问题(回显), 连接是活的
                        continue;
                    }
                    if ("agent.status".equals(name) || "task.status".equals(name)) {
                        gotHttp = true;
                        // 状态事件只记日志(截断 160 字); 是否收尾只看文字静默 / message_done
                        String st = payload.toString();
                        log(name + " " + (st.length() > 160 ? st.substring(0, 160) : st));
                        continue;
                    }
                    if ("delta.text_append".equals(name)) {
                        String t = payload.optString("text", "");
                        if (!t.isEmpty()) {
                            gotHttp = true;
                            if (ss.all.length() == 0)
                                log("first text after " + (System.currentTimeMillis() - startedAt) + " ms");
                            inTurn = true;
                            sawDelta = true;
                            cur.append(t);
                            ss.feed(t);   // 按句切出, 回调给播报
                            quietUntil = System.currentTimeMillis() + TEXT_QUIET_MS;
                            endReason = "text quiet " + TEXT_QUIET_MS + "ms";
                        }
                    } else if ("delta.message_done".equals(name)
                            || "message.assistant".equals(name)) {
                        gotHttp = true;
                        String txt = payload.optString("display_text", "");
                        if (txt.isEmpty()) {
                            JSONArray items = payload.optJSONArray("content");
                            if (items != null) {
                                StringBuilder t = new StringBuilder();
                                for (int k = 0; k < items.length(); k++) {
                                    JSONObject it = items.optJSONObject(k);
                                    if (it != null && "text".equals(it.optString("type")))
                                        t.append(it.optString("text", ""));
                                }
                                txt = t.toString();
                            } else {
                                txt = payload.optString("content", "");
                            }
                        }
                        // 没有收到过增量文字(整条消息一次给出)才采用整条文字; 同一条消息的
                        // delta.message_done 和 message.assistant 可能先后都到, 不能重复播
                        if (cur.length() == 0 && !sawDelta && !txt.trim().isEmpty()
                                && ss.all.indexOf(txt.trim()) < 0) {
                            ss.feed(txt);
                        }
                        cur.setLength(0);
                        ss.flushRemainder();     // 本条消息剩余文字作为一句
                        ss.messageBoundary();
                        if (ss.all.toString().trim().length() > 0) {
                            inTurn = true;
                            // 再等一小会看还有没有下一段消息, 没有就结束
                            quietUntil = System.currentTimeMillis() + MSG_DONE_GRACE_MS;
                            endReason = "message_done";
                        }
                    }
                }
            }
            subBuf.reset();
            if (start < buf.length) subBuf.write(buf, start, buf.length - start);
        }
        // Flush any partial message (e.g. deadline / quiet hit mid-message).
        if (!"cancelled".equals(endReason)) ss.flushRemainder();
        String out = ss.all.toString().trim();
        // 干净结束: message_done 后 grace 期满(不是 deadline/文字静默/取消), 且没有残留的半条分片/半行
        lastTurnClean = "message_done".equals(endReason) && !reasm.hasPending() && subBuf.size() == 0;
        log("stream end (" + endReason + "): sentences=" + ss.emitted + " chars=" + out.length()
                + " in " + (System.currentTimeMillis() - startedAt) + " ms" + (lastTurnClean ? " clean" : ""));
        return out;
    }


    // ---------------- warm-up / keep-alive ----------------
    /**
     * 开机后台调用: 提前连好 Muse, 之后每 25 秒发一次 WS ping 并把空闲期间服务器推来的
     * 事件读掉(必须按顺序解密, 否则 Noise nonce 会错位)。连续 2 次 ping 都没有任何回包(服务器回过 pong
     * 才启用这条判断)就重连; 网络恢复/打断后被 interrupt 立即重连。建连在对象锁外进行, 不挡对话。
     */
    public synchronized void startKeepAlive() {
        if (keepAliveThread != null) return;
        keepAliveRunning = true;
        keepAliveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                long backoff = 5_000;
                while (keepAliveRunning) {
                    boolean ok = tick();
                    long sleep = ok ? KEEPALIVE_MS : backoff;
                    backoff = ok ? 5_000 : Math.min(backoff * 2, MAX_BACKOFF_MS);
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException e) {
                        if (!keepAliveRunning) return;
                        backoff = 5_000;   // 网络恢复 / 需要立即重连: 马上再来一轮
                    }
                }
            }
        }, "GadgetKeepAlive");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    private boolean tick() {
        try {
            if (creds != null) creds.refreshIfDue();   // DeviceCredentials 自己同步, 不占本对象锁
            boolean need;
            int gen;
            synchronized (this) {
                if (reconnectNow) {
                    reconnectNow = false;
                    if (sock != null) {
                        log("keep-alive: reconnect requested, drop current connection");
                        closeQuiet();
                    }
                }
                if (sock != null && noise != null) {
                    long now = System.currentTimeMillis();
                    if (lastPingAt > 0 && lastRxAt < lastPingAt) missedPongs++;
                    else missedPongs = 0;
                    if (pongSeen && missedPongs >= 2) {
                        log("keep-alive: no pong for " + missedPongs + " pings ("
                                + ((now - lastRxAt) / 1000) + "s silent), reconnect");
                        closeQuiet();
                    } else {
                        wsPing();
                        lastPingAt = System.currentTimeMillis();
                        drainIdle();
                        return true;
                    }
                }
                need = true;
                gen = connGen;
            }
            if (need) {
                long t0 = System.currentTimeMillis();
                Conn c;
                warmConnecting = true;
                try {
                    c = openConn();   // 不持有对象锁: 对话线程此时可以照常用/自己建连
                } catch (Exception e) {
                    synchronized (this) {
                        warmConnecting = false;
                        notifyAll();
                    }
                    throw e;
                }
                synchronized (this) {
                    warmConnecting = false;   // 装好后才清标志, 等待中的对话线程醒来就能直接用
                    notifyAll();
                    if (connGen != gen && sock != null) {
                        c.closeQuiet();
                        log("warm connect: chat already reconnected, drop spare connection");
                        return true;
                    }
                    install(c);
                }
                log("warm connect ok in " + (System.currentTimeMillis() - t0) + " ms");
            }
            return true;
        } catch (Exception e) {
            log("keep-alive failed, will reconnect: " + e);
            synchronized (this) {
                closeQuiet();
            }
            return false;
        }
    }

    // ---------------- network change ----------------
    /**
     * 注册 CONNECTIVITY_CHANGE: 断网立即关掉连接(正在等回复的对话马上失败回落, 不再干等);
     * 网络恢复 / 换网络立即在后台重连。只需调用一次。
     */
    public void watchNetwork(android.content.Context context) {
        if (context == null) return;
        try {
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent intent) {
                    try {
                        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                                c.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
                        android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
                        boolean up = ni != null && ni.isConnected();
                        String key = up ? ni.getType() + "/" + ni.getExtraInfo() : "";
                        onNetworkChange(up, key, isInitialStickyBroadcast());
                    } catch (Throwable t) {
                        log("network receiver error: " + t);
                    }
                }
            };
            context.getApplicationContext().registerReceiver(r,
                    new android.content.IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION));
            log("network watcher registered");
        } catch (Throwable t) {
            log("network watcher register failed: " + t);
        }
    }

    private void onNetworkChange(boolean up, String key, boolean initial) {
        Boolean prev = lastNetUp;
        String prevKey = lastNetKey;
        lastNetUp = up;
        lastNetKey = key;
        if (initial || prev == null) return;
        if (!up) {
            if (prev) {
                log("network down: drop Muse connection now");
                abortSocket();
            }
            return;
        }
        if (!prev || !key.equals(prevKey)) {
            log("network up (" + (prev ? "changed" : "restored") + "): reconnect Muse now");
            reconnectNow = true;
            abortSocket();
            Thread t = keepAliveThread;
            if (t != null) t.interrupt();
        }
    }

    /** 不拿锁直接关 socket: 正阻塞在读上的线程立即出异常, 它自己会清理并重连。 */
    private void abortSocket() {
        Socket s = sock;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private void wsPing() throws IOException {
        byte[] m = new byte[4];
        maskRnd.nextBytes(m);
        out.write(new byte[]{(byte) 0x89, (byte) 0x80, m[0], m[1], m[2], m[3]});
        out.flush();
    }

    /** 读掉空闲期间的帧(解密以保持 nonce 同步), 内容丢弃; 控制流请求照常回复。最多 2 秒。 */
    private void drainIdle() throws Exception {
        long t0 = System.currentTimeMillis();
        sock.setSoTimeout(500);
        while (System.currentTimeMillis() - t0 < 2_000) {
            byte[] frame;
            try {
                frame = wsRecv();
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
            dispatchIdleFrame(noise.decrypt(frame));
            sock.setSoTimeout(300);
        }
        subBuf.reset();
    }

    /** 空闲期间收到的帧: 控制流交给 handleControl, 订阅流/控制流被重置则抛出(触发重连), 其余丢弃。 */
    private void dispatchIdleFrame(byte[] plain) throws IOException {
        try {
            byte[] whole = reasm.feed(plain);
            if (whole == null) return;
            GadgetProto.ServerFrame sf = GadgetProto.decodeServiceResponse(whole);
            if (sf.isReset && (sf.streamId == subStream || sf.streamId == controlStream))
                throw new IOException("stream " + sf.streamId + " reset while idle");
            if (sf.streamId == controlStream && !sf.isReset) handleControl(sf);
        } catch (IllegalArgumentException ignored) {
        }
    }

    /**
     * 发问前调用(已持有锁): 读掉连接上已到达的残余帧(上一轮提前返回后的 message_done、状态事件等),
     * 解密以保持 Noise nonce 同步, 内容丢弃。连续 150ms 没新帧即结束; 最多 1.5 秒。
     * 上一轮干净结束时首次只探测 PRE_DRAIN_PROBE_MS(30ms): 探测到任何帧就退回 150ms 静默规则照旧读干净。
     */
    private void drainPending() throws Exception {
        if (sock == null || noise == null) return;
        final boolean clean = lastTurnClean;
        lastTurnClean = false;
        long t0 = System.currentTimeMillis();
        int frames = 0;
        try {
            sock.setSoTimeout((int) (clean ? PRE_DRAIN_PROBE_MS : PRE_DRAIN_QUIET_MS));
            while (System.currentTimeMillis() - t0 < 1500) {
                byte[] frame;
                try {
                    frame = wsRecv();
                } catch (java.net.SocketTimeoutException e) {
                    break;
                }
                frames++;
                dispatchIdleFrame(noise.decrypt(frame));
                // 有残余帧: 不管上一轮是否干净, 之后都按 150ms 静默读完
                sock.setSoTimeout((int) PRE_DRAIN_QUIET_MS);
            }
        } finally {
            subBuf.reset();
        }
        if (frames > 0) log("pre-chat drain: dropped " + frames + " leftover frames in "
                + (System.currentTimeMillis() - t0) + " ms" + (clean ? " (after clean turn)" : ""));
        else if (clean) log("pre-chat drain: clean probe " + (System.currentTimeMillis() - t0) + " ms");
    }

    public synchronized void close() {
        closeQuiet();
    }

    /** 持有锁(或建连前)调用 */
    private void closeQuiet() {
        Socket s = sock;
        try {
            if (s != null) s.close();
        } catch (IOException ignored) {
        }
        sock = null;
        noise = null;
        lastTurnClean = false;
    }

    // ---------------- WebSocket framing ----------------
    private final java.security.SecureRandom maskRnd = new java.security.SecureRandom();

    private void wsSend(byte[] payload) throws IOException {
        wsSend(out, payload);
    }

    private void wsSend(OutputStream o, byte[] payload) throws IOException {
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        h.write(0x82);
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
        maskRnd.nextBytes(mask);
        h.write(mask, 0, 4);
        byte[] masked = new byte[n];
        for (int i = 0; i < n; i++) masked[i] = (byte) (payload[i] ^ mask[i % 4]);
        o.write(h.toByteArray());
        o.write(masked);
        o.flush();
    }

    private byte[] wsRecv() throws IOException {
        Socket s = sock;
        if (s == null) throw new EOFException("not connected");
        return wsRecv(s, in, out);
    }

    /**
     * Read one full message; handles fragmentation, answers ping.
     * 只允许在"一个消息的第一帧的首字节之前"按调用方设置的读超时抛 SocketTimeoutException(什么都没读,
     * 不丢字节); 一旦读到首字节, 剩余部分改用 MID_FRAME_TIMEOUT_MS 读完, 中途超时视为连接坏了
     * (抛普通 IOException, 不会被调用方当成"暂时没数据"而 continue, 避免帧错位)。
     */
    private byte[] wsRecv(Socket s, InputStream i, OutputStream o) throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        int callerTimeout = s.getSoTimeout();
        boolean extended = false;    // 已切到帧中途的长超时
        boolean inMessage = false;   // 已收到非 fin 的数据帧(分片消息中途)
        try {
            while (true) {
                int b0 = i.read();   // 帧首字节: 不在消息中途时允许超时
                if (b0 < 0) throw new EOFException("connection closed");
                if (!extended) {
                    s.setSoTimeout(MID_FRAME_TIMEOUT_MS);
                    extended = true;
                }
                int b1 = readFully(i, 1)[0] & 0xFF;
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    byte[] e = readFully(i, 2);
                    len = ((e[0] & 0xFF) << 8) | (e[1] & 0xFF);
                } else if (len == 127) {
                    byte[] e = readFully(i, 8);
                    len = 0;
                    for (byte b : e) len = (len << 8) | (b & 0xFF);
                }
                byte[] mask = masked ? readFully(i, 4) : null;
                if (len > 8 * 1024 * 1024) throw new IOException("ws frame too large");
                byte[] data = readFully(i, (int) len);
                if (masked) {
                    for (int k = 0; k < data.length; k++) data[k] ^= mask[k % 4];
                }
                lastRxAt = System.currentTimeMillis();
                if (opcode == 0x8) throw new EOFException("server closed websocket");
                if (opcode == 0x9 || opcode == 0xA) {
                    if (opcode == 0x9) {  // ping -> pong
                        ByteArrayOutputStream p = new ByteArrayOutputStream();
                        p.write(0x8A);
                        p.write(0x80 | data.length);
                        byte[] m = new byte[4];
                        maskRnd.nextBytes(m);
                        p.write(m, 0, 4);
                        for (int k = 0; k < data.length; k++) data[k] ^= m[k % 4];
                        o.write(p.toByteArray());
                        o.write(data);
                        o.flush();
                    } else if (!pongSeen) {
                        pongSeen = true;   // 服务器会回 pong: 之后启用"2 次无 pong 重连"
                        log("keep-alive: server answers ping");
                    }
                    if (!inMessage) {      // 控制帧之间回到帧边界: 恢复调用方超时
                        s.setSoTimeout(callerTimeout);
                        extended = false;
                    }
                    continue;
                }
                if (opcode == 0x2 || opcode == 0x0) msg.write(data, 0, data.length);
                if (fin) return msg.toByteArray();
                inMessage = true;
            }
        } catch (java.net.SocketTimeoutException e) {
            if (extended) throw new IOException("ws frame stalled mid-frame (" + MID_FRAME_TIMEOUT_MS + " ms)");
            throw e;
        } finally {
            if (extended) {
                try { s.setSoTimeout(callerTimeout); } catch (IOException ignored) { }
            }
        }
    }

    private static byte[] readFully(InputStream i, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = i.read(b, off, n - off);
            if (r < 0) throw new EOFException("connection closed");
            off += r;
        }
        return b;
    }


    private static byte[] readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream h = new ByteArrayOutputStream();
        int[] last = {-1, -1, -1, -1};
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException("no http response");
            h.write(b);
            last[0] = last[1]; last[1] = last[2]; last[2] = last[3]; last[3] = b;
            if (last[0] == '\r' && last[1] == '\n' && last[2] == '\r' && last[3] == '\n')
                return h.toByteArray();
            if (h.size() > 65536) throw new IOException("header too large");
        }
    }

    private static int parseStatus(byte[] head) throws IOException {
        String s = new String(head, StandardCharsets.US_ASCII);
        int eol = s.indexOf("\r\n");
        String[] parts = s.substring(0, eol).split(" ", 3);
        if (parts.length < 2) throw new IOException("bad status line");
        return Integer.parseInt(parts[1]);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int r;
        while ((r = in.read(tmp)) >= 0) b.write(tmp, 0, r);
        return b.toByteArray();
    }
}