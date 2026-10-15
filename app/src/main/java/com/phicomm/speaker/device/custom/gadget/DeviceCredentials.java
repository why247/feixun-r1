package com.phicomm.speaker.device.custom.gadget;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Device credentials from Muse BLE pairing (official muse-gadget-sdk):
 *   files/muse_pairing.json  (access_token, refresh_token, api_url_v2, noise_host, access_token_saved_at)
 *   files/muse_identity.json (mac)  -> node_id = "homelink-" + last 6 hex of mac
 * Refresh rotates BOTH tokens, so the new pair is persisted atomically.
 * Token values are never logged.
 */
public final class DeviceCredentials {
    public static final String PAIRING_FILE = "muse_pairing.json";
    public static final String IDENTITY_FILE = "muse_identity.json";
    private static final String DEFAULT_API = "https://api.muse.ai";
    private static final String DEFAULT_NOISE_HOST = "hatch.metaaivm.com";
    private static final long REFRESH_AGE_S = 3 * 3600;
    private static final long RETRY_GAP_MS = 5 * 60_000;

    private final File dir;
    private JSONObject pairing;
    private final String nodeId;
    private long lastRefreshAttempt;

    private DeviceCredentials(File dir, JSONObject pairing, String nodeId) {
        this.dir = dir;
        this.pairing = pairing;
        this.nodeId = nodeId;
    }

    /** Returns null when this device has not been paired (files missing/invalid). */
    public static DeviceCredentials load(File dir) {
        try {
            File p = new File(dir, PAIRING_FILE);
            File i = new File(dir, IDENTITY_FILE);
            if (!p.isFile() || !i.isFile()) return null;
            JSONObject pairing = new JSONObject(readText(p));
            JSONObject ident = new JSONObject(readText(i));
            String mac = ident.optString("mac", "").replace(":", "").toLowerCase();
            if (mac.length() < 6) return null;
            if (pairing.optString("access_token", "").isEmpty()
                    || pairing.optString("refresh_token", "").isEmpty()) return null;
            return new DeviceCredentials(dir, pairing, "homelink-" + mac.substring(mac.length() - 6));
        } catch (Exception e) {
            return null;
        }
    }

    public String nodeId() { return nodeId; }

    public synchronized String accessToken() { return pairing.optString("access_token", ""); }

    public synchronized String apiRoot() {
        String v2 = pairing.optString("api_url_v2", "");
        String r = v2.startsWith("https://") ? v2 : DEFAULT_API;
        while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
        return r;
    }

    public synchronized String noiseHost() {
        String h = pairing.optString("noise_host", "");
        return h.isEmpty() ? DEFAULT_NOISE_HOST : h;
    }

    /** Refresh if the access token is older than 3h. Failures are ignored (old token kept). */
    public synchronized void refreshIfDue() {
        long age = System.currentTimeMillis() / 1000 - pairing.optLong("access_token_saved_at", 0);
        if (age < REFRESH_AGE_S) return;
        if (System.currentTimeMillis() - lastRefreshAttempt < RETRY_GAP_MS) return;
        try { refresh(); } catch (IOException ignored) { }
    }

    /** Force refresh (after a 401). Throws on failure; HTTP 401 here means pairing revoked. */
    public synchronized void refresh() throws IOException {
        lastRefreshAttempt = System.currentTimeMillis();
        String rt = pairing.optString("refresh_token", "");
        if (rt.startsWith("hatch_refresh:")) rt = rt.substring("hatch_refresh:".length());
        HttpURLConnection c = (HttpURLConnection) new URL(apiRoot() + "/device_token/refresh").openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer hatch_refresh:" + rt);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "musegadget/0.1.0 (Phicomm R1; Android) Java");
            byte[] body = new JSONObject().put("device_id", nodeId).toString()
                    .getBytes(StandardCharsets.UTF_8);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("token refresh failed (HTTP " + status + ")");
            JSONObject data = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
            JSONObject payload = data.optJSONObject("payload");
            if (payload != null) data = payload;
            String at = data.optString("access_token", "");
            String nrt = data.optString("refresh_token", "");
            if (at.isEmpty() || nrt.isEmpty()) throw new IOException("token refresh: missing tokens");
            JSONObject next = new JSONObject(pairing.toString());
            next.put("access_token", at);
            next.put("refresh_token", nrt);
            next.put("access_token_saved_at", System.currentTimeMillis() / 1000);
            save(next);
            pairing = next;
        } catch (JSONException e) {
            throw new IOException("token refresh: bad JSON");
        } finally {
            c.disconnect();
        }
    }

    private void save(JSONObject obj) throws IOException {
        File tmp = new File(dir, PAIRING_FILE + ".tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        try {
            fo.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            fo.getFD().sync();
        } finally {
            fo.close();
        }
        tmp.setReadable(false, false);
        tmp.setReadable(true, true);
        if (!tmp.renameTo(new File(dir, PAIRING_FILE)))
            throw new IOException("cannot save pairing");
    }

    private static String readText(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try { return new String(readAll(in), StandardCharsets.UTF_8); } finally { in.close(); }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] t = new byte[4096];
        int r;
        while ((r = in.read(t)) >= 0) b.write(t, 0, r);
        return b.toByteArray();
    }
}
