package com.phicomm.speaker.device.custom.gadget;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * X25519 (RFC 7748) implemented with BigInteger Montgomery ladder.
 * No platform crypto dependency (works on any Android API level).
 * Scalars are ephemeral per handshake, so ladder speed (~ms) is irrelevant.
 */
public final class X25519 {
    private static final BigInteger P =
            BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);

    private X25519() {}

    /** Clamp a 32-byte scalar per RFC 7748. */
    public static byte[] clamp(byte[] s) {
        byte[] c = s.clone();
        c[0] &= 0xF8;
        c[31] &= 0x7F;
        c[31] |= 0x40;
        return c;
    }

    public static byte[] generatePrivateKey(SecureRandom rnd) {
        byte[] s = new byte[32];
        rnd.nextBytes(s);
        return clamp(s);
    }

    public static byte[] publicKey(byte[] privateKey) {
        byte[] base = new byte[32];
        base[0] = 9;
        return scalarMult(clamp(privateKey), base);
    }

    /**
     * X25519 scalar multiplication. Both inputs 32-byte little-endian.
     * scalar should already be clamped (publicKey() clamps for you).
     */
    public static byte[] scalarMult(byte[] scalar, byte[] u) {
        BigInteger x1 = leToBig(u).clearBit(255);
        BigInteger x2 = BigInteger.ONE;
        BigInteger z2 = BigInteger.ZERO;
        BigInteger x3 = x1;
        BigInteger z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int kt = (scalar[t >> 3] >> (t & 7)) & 1;
            swap ^= kt;
            if (swap != 0) {
                BigInteger tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = kt;

            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);

            BigInteger x3n = da.add(cb).mod(P);
            x3n = x3n.multiply(x3n).mod(P);
            BigInteger z3n = da.subtract(cb).mod(P);
            z3n = z3n.multiply(z3n).mod(P);
            z3n = x1.multiply(z3n).mod(P);
            BigInteger x2n = aa.multiply(bb).mod(P);
            BigInteger z2n = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P);

            x2 = x2n; z2 = z2n; x3 = x3n; z3 = z3n;
        }
        if (swap != 0) {
            BigInteger tmp = x2; x2 = x3; x3 = tmp;
            tmp = z2; z2 = z3; z3 = tmp;
        }
        BigInteger z2inv = z2.modPow(P.subtract(BigInteger.valueOf(2)), P);
        return bigToLe(x2.multiply(z2inv).mod(P));
    }

    private static BigInteger leToBig(byte[] le) {
        byte[] be = new byte[le.length];
        for (int i = 0; i < le.length; i++) be[i] = le[le.length - 1 - i];
        return new BigInteger(1, be);
    }

    private static byte[] bigToLe(BigInteger v) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[32];
        int n = Math.min(be.length, 32);
        for (int i = 0; i < n; i++) out[i] = be[be.length - 1 - i];
        return out;
    }
}
