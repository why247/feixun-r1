package com.phicomm.speaker.device.custom.gadget;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM in pure Java, built only on AES/ECB/NoPadding
 * (available on every Android API level, incl. the R1's API 22).
 *
 * Avoids Cipher.getInstance("AES/GCM/NoPadding"), whose availability on
 * old Android versions cannot be verified without the device.
 * Validated against OpenSSL-generated vectors in TestGadget.
 */
public final class AesGcm {
    private static final int TAG_LEN = 16;

    private AesGcm() {}

    public static byte[] encrypt(byte[] key, byte[] iv12, byte[] plaintext, byte[] aad)
            throws GeneralSecurityException {
        if (iv12.length != 12) throw new GeneralSecurityException("IV must be 12 bytes");
        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] h = aes.doFinal(new byte[16]);

        byte[] j0 = new byte[16];
        System.arraycopy(iv12, 0, j0, 0, 12);
        j0[15] = 1;

        byte[] ct = gctr(aes, incr(j0), plaintext);
        byte[] s = ghash(h, aad, ct);
        byte[] tagFull = gctr(aes, j0, s);
        byte[] tag = Arrays.copyOf(tagFull, TAG_LEN);

        byte[] out = new byte[ct.length + TAG_LEN];
        System.arraycopy(ct, 0, out, 0, ct.length);
        System.arraycopy(tag, 0, out, ct.length, TAG_LEN);
        return out;
    }

    public static byte[] decrypt(byte[] key, byte[] iv12, byte[] ctAndTag, byte[] aad)
            throws GeneralSecurityException {
        if (iv12.length != 12) throw new GeneralSecurityException("IV must be 12 bytes");
        if (ctAndTag.length < TAG_LEN) throw new GeneralSecurityException("too short");
        int clen = ctAndTag.length - TAG_LEN;
        byte[] ct = Arrays.copyOf(ctAndTag, clen);
        byte[] tag = Arrays.copyOfRange(ctAndTag, clen, ctAndTag.length);

        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] h = aes.doFinal(new byte[16]);

        byte[] j0 = new byte[16];
        System.arraycopy(iv12, 0, j0, 0, 12);
        j0[15] = 1;

        byte[] s = ghash(h, aad, ct);
        byte[] expectFull = gctr(aes, j0, s);
        for (int i = 0; i < TAG_LEN; i++) {
            if (tag[i] != expectFull[i])
                throw new GeneralSecurityException("GCM tag mismatch");
        }
        return gctr(aes, incr(j0), ct);
    }

    // GCTR: encrypt successive counter blocks, XOR with input
    private static byte[] gctr(Cipher aes, byte[] icb, byte[] in)
            throws GeneralSecurityException {
        byte[] out = new byte[in.length];
        byte[] cb = icb.clone();
        int off = 0;
        while (off < in.length) {
            byte[] ks = aes.doFinal(cb);
            int n = Math.min(16, in.length - off);
            for (int i = 0; i < n; i++) out[off + i] = (byte) (in[off + i] ^ ks[i]);
            off += n;
            incrInPlace(cb);
        }
        return out;
    }

    // GHASH(H, AAD, CT)
    private static byte[] ghash(byte[] h, byte[] aad, byte[] ct) {
        byte[] x = new byte[16];
        for (int off = 0; off < aad.length; off += 16) {
            byte[] blk = new byte[16];
            int n = Math.min(16, aad.length - off);
            System.arraycopy(aad, off, blk, 0, n);
            x = gmul(xor(x, blk), h);
        }
        for (int off = 0; off < ct.length; off += 16) {
            byte[] blk = new byte[16];
            int n = Math.min(16, ct.length - off);
            System.arraycopy(ct, off, blk, 0, n);
            x = gmul(xor(x, blk), h);
        }
        byte[] lenBlk = new byte[16];
        putU64(lenBlk, 0, (long) aad.length * 8);
        putU64(lenBlk, 8, (long) ct.length * 8);
        x = gmul(xor(x, lenBlk), h);
        return x;
    }

    private static void putU64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (56 - 8 * i));
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] o = new byte[16];
        for (int i = 0; i < 16; i++) o[i] = (byte) (a[i] ^ b[i]);
        return o;
    }

    private static byte[] incr(byte[] cb) {
        byte[] o = cb.clone();
        incrInPlace(o);
        return o;
    }

    private static void incrInPlace(byte[] cb) {
        for (int i = 15; i >= 12; i--) {
            cb[i]++;
            if (cb[i] != 0) break;
        }
    }

    /**
     * GF(2^128) multiplication, GCM polynomial x^128 + x^7 + x^2 + x + 1.
     * Standard shift-and-XOR ("Russian peasant") algorithm.
     */
    static byte[] gmul(byte[] x, byte[] y) {
        byte[] z = new byte[16];
        byte[] v = y.clone();
        for (int i = 0; i < 128; i++) {
            int bit = (x[i >> 3] >> (7 - (i & 7))) & 1;
            if (bit != 0) {
                for (int j = 0; j < 16; j++) z[j] ^= v[j];
            }
            // v = v >> 1 with reduction
            int lsb = v[15] & 1;
            for (int j = 15; j > 0; j--) {
                v[j] = (byte) (((v[j] & 0xFF) >>> 1) | ((v[j - 1] & 1) << 7));
            }
            v[0] = (byte) ((v[0] & 0xFF) >>> 1);
            if (lsb != 0) v[0] ^= (byte) 0xE1; // R = 11100001
        }
        return z;
    }
}
