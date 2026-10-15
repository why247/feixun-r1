package com.phicomm.speaker.device.custom.gadget;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal protobuf codecs for the Muse gadget wire format.
 * Java port of the Python prototype. No protobuf dependency.
 *
 * TransportFrame: f1 chunk_id, f2 chunk_index (omit if 0), f3 total_chunks, f4 payload
 * ServiceRequestEnvelope: f1 service (omit if 0=Daemon), f2 payload (ServiceFrame)
 * ServiceFrame: f1 stream_id, f2 request{1:verb,2:path,3:headers[{1:key,2:value}],4:body,5:end_body}
 *   server->client: f3 response{1:status,3:body,4:end_body} | f4 body_chunk{1:data,2:end_body}
 */
public final class GadgetProto {
    private GadgetProto() {}

    private static final SecureRandom RND = new SecureRandom();

    // ---------- encode ----------
    private static void varint(ByteArrayOutputStream out, long n) {
        while (true) {
            int b = (int) (n & 0x7F);
            n >>>= 7;
            if (n != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return;
            }
        }
    }

    private static void key(ByteArrayOutputStream out, int field, int wire) {
        varint(out, ((long) field << 3) | wire);
    }

    private static void vfield(ByteArrayOutputStream out, int field, long n) {
        if (n == 0) return;
        key(out, field, 0);
        varint(out, n);
    }

    private static void bytesField(ByteArrayOutputStream out, int field, byte[] data) {
        if (data == null || data.length == 0) return;
        key(out, field, 2);
        varint(out, data.length);
        out.write(data, 0, data.length);
    }

    private static void strField(ByteArrayOutputStream out, int field, String s) {
        if (s == null || s.isEmpty()) return;
        bytesField(out, field, s.getBytes(StandardCharsets.UTF_8));
    }

    /** headers: flat array {k1, v1, k2, v2, ...}. */
    public static byte[] encodeRequestEnvelope(int streamId, String verb, String path,
                                               String[] headers, byte[] body, boolean endBody) {
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        strField(req, 1, verb);
        strField(req, 2, path);
        if (headers != null) {
            for (int i = 0; i + 1 < headers.length; i += 2) {
                ByteArrayOutputStream h = new ByteArrayOutputStream();
                strField(h, 1, headers[i]);
                strField(h, 2, headers[i + 1]);
                bytesField(req, 3, h.toByteArray());
            }
        }
        bytesField(req, 4, body);
        if (endBody) vfield(req, 5, 1);

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        vfield(frame, 1, streamId);
        bytesField(frame, 2, req.toByteArray());

        ByteArrayOutputStream env = new ByteArrayOutputStream();
        bytesField(env, 2, frame.toByteArray());  // service=Daemon(0) omitted
        return env.toByteArray();
    }

    /** Single-chunk transport frame (chunk_index 0 omitted). */
    public static byte[] encodeTransportFrame(byte[] payload) {
        long chunkId = RND.nextLong();
        if (chunkId == 0) chunkId = 1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        vfield(out, 1, chunkId);
        vfield(out, 3, 1);
        bytesField(out, 4, payload);
        return out.toByteArray();
    }

    // ---------- decode ----------
    public static final class Field {
        public final int wire;
        public final long varint;
        public final byte[] bytes;
        Field(int wire, long varint, byte[] bytes) {
            this.wire = wire; this.varint = varint; this.bytes = bytes;
        }
    }

    public static Map<Integer, List<Field>> parse(byte[] buf) {
        Map<Integer, List<Field>> fields = new HashMap<>();
        int pos = 0;
        while (pos < buf.length) {
            long[] r = readVarint(buf, pos);
            long key = r[0]; pos = (int) r[1];
            int field = (int) (key >>> 3), wire = (int) (key & 7);
            Field f;
            if (wire == 0) {
                r = readVarint(buf, pos); pos = (int) r[1];
                f = new Field(wire, r[0], null);
            } else if (wire == 2) {
                r = readVarint(buf, pos); pos = (int) r[1];
                int len = (int) r[0];
                byte[] b = new byte[len];
                System.arraycopy(buf, pos, b, 0, len);
                pos += len;
                f = new Field(wire, 0, b);
            } else if (wire == 5) {
                byte[] b = new byte[4];
                System.arraycopy(buf, pos, b, 0, 4); pos += 4;
                f = new Field(wire, 0, b);
            } else if (wire == 1) {
                byte[] b = new byte[8];
                System.arraycopy(buf, pos, b, 0, 8); pos += 8;
                f = new Field(wire, 0, b);
            } else {
                throw new IllegalArgumentException("bad wire type " + wire);
            }
            List<Field> list = fields.get(field);
            if (list == null) {
                list = new ArrayList<Field>();
                fields.put(field, list);
            }
            list.add(f);
        }
        return fields;
    }

    private static long[] readVarint(byte[] buf, int pos) {
        long n = 0;
        int shift = 0;
        while (true) {
            int b = buf[pos++] & 0xFF;
            n |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return new long[]{n, pos};
            shift += 7;
            if (shift >= 64) throw new IllegalArgumentException("varint overflow");
        }
    }

    private static byte[] firstBytes(Map<Integer, List<Field>> f, int field) {
        List<Field> l = f.get(field);
        if (l != null) for (Field x : l) if (x.wire == 2) return x.bytes;
        return new byte[0];
    }

    private static long firstVarint(Map<Integer, List<Field>> f, int field) {
        List<Field> l = f.get(field);
        if (l != null) for (Field x : l) if (x.wire == 0) return x.varint;
        return 0;
    }

    public static final class ServerFrame {
        public int streamId;
        public boolean isResponse;   // else body_chunk
        public int status;           // response only
        public byte[] body;          // response.body or chunk.data
        public boolean endBody;
    }

    /** Decode one decrypted transport payload. */
    public static ServerFrame decodeServerFrame(byte[] transportPlaintext) {
        byte[] payload = firstBytes(parse(transportPlaintext), 4);
        if (payload.length == 0) throw new IllegalArgumentException("empty transport payload");
        byte[] frameBytes = firstBytes(parse(payload), 2);
        if (frameBytes.length == 0) throw new IllegalArgumentException("empty service frame");
        Map<Integer, List<Field>> fr = parse(frameBytes);
        ServerFrame sf = new ServerFrame();
        sf.streamId = (int) firstVarint(fr, 1);
        if (fr.containsKey(3)) {
            Map<Integer, List<Field>> r = parse(firstBytes(fr, 3));
            sf.isResponse = true;
            sf.status = (int) firstVarint(r, 1);
            sf.body = firstBytes(r, 3);
            sf.endBody = firstVarint(r, 4) != 0;
        } else if (fr.containsKey(4)) {
            Map<Integer, List<Field>> c = parse(firstBytes(fr, 4));
            sf.isResponse = false;
            sf.body = firstBytes(c, 1);
            sf.endBody = firstVarint(c, 2) != 0;
        } else {
            throw new IllegalArgumentException("unknown service frame kind");
        }
        return sf;
    }
}
