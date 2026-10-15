package com.phicomm.speaker.device.custom.gadget;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
    private static final String FETCH_HOST = "api.muse.ai";
    private static final String VM_HOST = "hatch.metaaivm.com";
    private static final int VM_PORT = 443;
    private static final String WS_KEY = "dGhlIHNhbXBsZSBub25jZQ==";

    private static final long REPLY_TIMEOUT_MS = 60_000;
    private static final long SETTLE_QUIET_MS = 2_500;

    private final String sdkToken;
    private Socket sock;
    private InputStream in;
    private OutputStream out;
    private NoiseXX noise;
    private int nextStream = 1;
    private int subStream;
    private final ByteArrayOutputStream subBuf = new ByteArrayOutputStream();

    public GadgetClient(String sdkToken) {
        this.sdkToken = sdkToken;
    }

    // ---------------- fetch_vms ----------------
    private static final class VmInfo {
        String vmId, vmName, vmToken;
    }

    private VmInfo fetchVms() throws IOException {
        try {
            return fetchVmsInner();
        } catch (JSONException e) {
            throw new IOException("fetch_vms: bad JSON: " + e.getMessage());
        }
    }

    private VmInfo fetchVmsInner() throws IOException, JSONException {
        SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket s = (SSLSocket) f.createSocket(FETCH_HOST, 443)) {
            s.setSoTimeout(15_000);
            String req = "GET /fetch_vms HTTP/1.0\r\n"
                    + "Host: " + FETCH_HOST + "\r\n"
                    + "Authorization: Bearer " + sdkToken + "\r\n"
                    + "X-API-Version: 1.0.0\r\n"
                    + "Connection: close\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            byte[] head = readHeaders(s.getInputStream());
            int status = parseStatus(head);
            byte[] body = readAll(s.getInputStream());
            if (status == 401 || status == 403)
                throw new IOException("token rejected (HTTP " + status + ")");
            if (status != 200)
                throw new IOException("fetch_vms failed (HTTP " + status + ")");
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
    public synchronized void connect() throws Exception {
        close();
        VmInfo vm = fetchVms();
        SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket s = (SSLSocket) f.createSocket(VM_HOST, VM_PORT);
        s.setSoTimeout(15_000);
        sock = s;
        in = s.getInputStream();
        out = s.getOutputStream();

        String path = "/v1/noise?vm_id=" + URLEncoder.encode(vm.vmId, "UTF-8");
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + VM_HOST + "\r\n"
                + "Authorization: Bearer " + vm.vmToken + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + WS_KEY + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        int status = parseStatus(readHeaders(in));
        if (status == 401 || status == 403)
            throw new IOException("VM refused the token (HTTP " + status + ")");
        if (status != 101)
            throw new IOException("WS upgrade failed (HTTP " + status + ")");

        noise = new NoiseXX();
        wsSend(noise.writeMessage1());
        noise.readMessage2(wsRecv());
        wsSend(noise.writeMessage3(new byte[0]));
        nextStream = 1;
        subBuf.reset();
        subStream = openStream("POST", "/chat/subscribe",
                new String[]{"Content-Type", "application/json", "Accept", "application/x-ndjson"},
                "{}".getBytes(StandardCharsets.UTF_8), true);
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
    public synchronized String chat(String systemPrompt, String historyText,
                                    String userInput) throws Exception {
        if (sock == null || noise == null) {
            connect();   // first turn (PhicommChatHandler only news the client)
        }
        StringBuilder sb = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isEmpty())
            sb.append(systemPrompt).append("\n\n");
        if (historyText != null && !historyText.isEmpty()) {
            String h = historyText.length() > 1500
                    ? historyText.substring(historyText.length() - 1500) : historyText;
            sb.append(h).append("\n\n");
        }
        sb.append(userInput);
        byte[] body = new JSONObject()
                .put("message", sb.toString())
                .put("output_modality", "text")
                .toString().getBytes(StandardCharsets.UTF_8);
        subBuf.reset();  // drop any late events from the previous turn
        try {
            return doChat(body);
        } catch (Exception e) {
            connect();           // one reconnect attempt (covers IO + protocol errors)
            subBuf.reset();
            return doChat(body);
        }
    }

    private String doChat(byte[] body) throws Exception {
        openStream("POST", "/chat/stream",
                new String[]{"Content-Type", "application/json"}, body, true);
        long deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS;
        List<String> messages = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inTurn = false;
        long quietUntil = 0;

        while (true) {
            long now = System.currentTimeMillis();
            // Hard deadline: always exit, never hang (method is synchronized).
            // Return whatever text arrived (may be partial or empty).
            if (now >= deadline) break;
            if (inTurn && quietUntil > 0 && now >= quietUntil) break;
            sock.setSoTimeout((int) Math.max(500, Math.min(5_000, deadline - now)));
            byte[] frame;
            try {
                frame = wsRecv();
            } catch (java.net.SocketTimeoutException e) {
                continue;
            }
            byte[] plain;
            try {
                plain = noise.decrypt(frame);
            } catch (Exception e) {
                continue;
            }
            GadgetProto.ServerFrame sf;
            try {
                sf = GadgetProto.decodeServerFrame(plain);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (sf.streamId != subStream || sf.isResponse) continue;
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
                    if ("delta.text_append".equals(name)) {
                        String t = payload.optString("text", "");
                        if (!t.isEmpty()) {
                            inTurn = true;
                            cur.append(t);
                            quietUntil = 0;
                        }
                    } else if ("delta.message_done".equals(name)
                            || "message.assistant".equals(name)) {
                        String txt = payload.optString("display_text", "");
                        if (txt.isEmpty()) txt = payload.optString("content", "");
                        String full = cur.length() > 0 ? cur.toString() : txt;
                        if (!full.trim().isEmpty()) messages.add(full);
                        cur.setLength(0);
                        inTurn = true;
                        quietUntil = System.currentTimeMillis() + SETTLE_QUIET_MS;
                    }
                }
            }
            subBuf.reset();
            if (start < buf.length) subBuf.write(buf, start, buf.length - start);
        }
        // Flush any partial message (e.g. deadline hit mid-turn).
        if (cur.length() > 0 && cur.toString().trim().length() > 0)
            messages.add(cur.toString());
        StringBuilder out = new StringBuilder();
        for (String m : messages) {
            if (out.length() > 0) out.append('\n');
            out.append(m);
        }
        return out.toString().trim();
    }

    public synchronized void close() {
        try {
            if (sock != null) sock.close();
        } catch (IOException ignored) {
        }
        sock = null;
        noise = null;
    }

    // ---------------- WebSocket framing ----------------
    private final java.security.SecureRandom maskRnd = new java.security.SecureRandom();

    private void wsSend(byte[] payload) throws IOException {
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
            for (int i = 7; i >= 0; i--) h.write((int) ((n >>> (8 * i)) & 0xFF));
        }
        byte[] mask = new byte[4];
        maskRnd.nextBytes(mask);
        h.write(mask, 0, 4);
        byte[] masked = new byte[n];
        for (int i = 0; i < n; i++) masked[i] = (byte) (payload[i] ^ mask[i % 4]);
        out.write(h.toByteArray());
        out.write(masked);
        out.flush();
    }

    /** Read one full message; handles fragmentation, answers ping. */
    private byte[] wsRecv() throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (true) {
            byte[] hdr = readFully(2);
            boolean fin = (hdr[0] & 0x80) != 0;
            int opcode = hdr[0] & 0x0F;
            boolean masked = (hdr[1] & 0x80) != 0;
            long len = hdr[1] & 0x7F;
            if (len == 126) {
                byte[] e = readFully(2);
                len = ((e[0] & 0xFF) << 8) | (e[1] & 0xFF);
            } else if (len == 127) {
                byte[] e = readFully(8);
                len = 0;
                for (byte b : e) len = (len << 8) | (b & 0xFF);
            }
            byte[] mask = masked ? readFully(4) : null;
            if (len > 8 * 1024 * 1024) throw new IOException("ws frame too large");
            byte[] data = readFully((int) len);
            if (masked) {
                for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
            }
            if (opcode == 0x8) throw new EOFException("server closed websocket");
            if (opcode == 0x9) {  // ping -> pong
                ByteArrayOutputStream p = new ByteArrayOutputStream();
                p.write(0x8A);
                p.write(0x80 | data.length);
                byte[] m = new byte[4];
                maskRnd.nextBytes(m);
                p.write(m, 0, 4);
                for (int i = 0; i < data.length; i++) data[i] ^= m[i % 4];
                out.write(p.toByteArray());
                out.write(data);
                out.flush();
                continue;
            }
            if (opcode == 0x2 || opcode == 0x0) msg.write(data, 0, data.length);
            if (fin) return msg.toByteArray();
        }
    }

    private byte[] readFully(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
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
