package com.phicomm.speaker.device.custom.gadget;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Noise_XX_25519_AESGCM_SHA256 initiator, initiator side.
 * Java port of the Python prototype (which mirrors Meta's noise_core).
 * The one deliberate deviation from the Noise spec: AES-GCM nonce =
 * 4 zero bytes + 8-byte BIG-endian counter (Meta's FormatAesGcmNonce).
 */
public final class NoiseXX {
    private static final byte[] PROTOCOL_NAME =
            "Noise_XX_25519_AESGCM_SHA256".getBytes(StandardCharsets.US_ASCII);

    private final MessageDigest sha256;
    private final Mac hmac;
    private final SecureRandom rnd = new SecureRandom();

    private byte[] h, ck, k;
    private long n;
    private byte[] ePriv, ePub, sPriv, sPub, re;
    private byte[] sendKey, recvKey;
    private long sendN, recvN;

    public NoiseXX() throws GeneralSecurityException {
        sha256 = MessageDigest.getInstance("SHA-256");
        hmac = Mac.getInstance("HmacSHA256");
        h = new byte[32];
        System.arraycopy(PROTOCOL_NAME, 0, h, 0, PROTOCOL_NAME.length);
        ck = h.clone();
        k = null;
        n = 0;
        ePriv = X25519.generatePrivateKey(rnd);
        ePub = X25519.publicKey(ePriv);
        sPriv = X25519.generatePrivateKey(rnd);
        sPub = X25519.publicKey(sPriv);
    }

    // ---- symmetric state ----
    private void mixHash(byte[] data) {
        sha256.reset();
        sha256.update(h);
        sha256.update(data);
        h = sha256.digest();
    }

    private void mixKey(byte[] ikm) throws GeneralSecurityException {
        byte[][] out = hkdf(ck, ikm);
        ck = out[0];
        k = out[1];
        n = 0;
    }

    /** HKDF-SHA256(salt=ck, ikm, info="") -> {ck', temp_k}. */
    private byte[][] hkdf(byte[] salt, byte[] ikm) throws GeneralSecurityException {
        hmac.reset();
        hmac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = hmac.doFinal(ikm);
        hmac.reset();
        hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] t1 = hmac.doFinal(new byte[]{0x01});
        hmac.reset();
        hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] t2in = new byte[t1.length + 1];
        System.arraycopy(t1, 0, t2in, 0, t1.length);
        t2in[t1.length] = 0x02;
        byte[] t2 = hmac.doFinal(t2in);
        return new byte[][]{t1, t2};
    }

    private static byte[] nonce(long ctr) {
        byte[] iv = new byte[12];
        for (int i = 0; i < 8; i++) iv[4 + i] = (byte) (ctr >>> (56 - 8 * i));
        return iv;
    }

    private byte[] encryptAndHash(byte[] pt) throws GeneralSecurityException {
        byte[] ct;
        if (k == null) {
            ct = pt;
        } else {
            ct = AesGcm.encrypt(k, nonce(n), pt, h);
            n++;
        }
        mixHash(ct);
        return ct;
    }

    private byte[] decryptAndHash(byte[] ct) throws GeneralSecurityException {
        byte[] pt;
        if (k == null) {
            pt = ct;
        } else {
            pt = AesGcm.decrypt(k, nonce(n), ct, h);
            n++;
        }
        mixHash(ct);
        return pt;
    }

    // ---- handshake ----
    /** -> e : returns 32-byte cleartext ephemeral public key. */
    public byte[] writeMessage1() throws GeneralSecurityException {
        mixHash(new byte[0]);
        mixHash(ePub);
        encryptAndHash(new byte[0]);
        return ePub.clone();
    }

    /**
     * <- e, ee, s, es : msg = re(32) || enc_s(48) || enc_payload.
     * Returns the decrypted payload (server "extra" data, ignored).
     */
    public byte[] readMessage2(byte[] msg) throws GeneralSecurityException {
        if (msg.length < 80) throw new GeneralSecurityException("msg2 too short");
        byte[] reB = new byte[32];
        System.arraycopy(msg, 0, reB, 0, 32);
        mixHash(reB);
        mixKey(X25519.scalarMult(X25519.clamp(ePriv), reB));            // ee
        byte[] encS = new byte[48];
        System.arraycopy(msg, 32, encS, 0, 48);
        byte[] rs = decryptAndHash(encS);                               // s
        mixKey(X25519.scalarMult(X25519.clamp(ePriv), rs));              // es
        byte[] encPayload = new byte[msg.length - 80];
        System.arraycopy(msg, 80, encPayload, 0, encPayload.length);
        byte[] extra = decryptAndHash(encPayload);
        re = reB;
        return extra;
    }

    /** -> s, se : returns enc(s_pub) || enc(payload). */
    public byte[] writeMessage3(byte[] payload) throws GeneralSecurityException {
        byte[] ct1 = encryptAndHash(sPub);                               // s
        mixKey(X25519.scalarMult(X25519.clamp(sPriv), re));               // se
        byte[] ct2 = encryptAndHash(payload);
        byte[][] keys = hkdf(ck, new byte[0]);                          // Split
        sendKey = keys[0];
        recvKey = keys[1];
        sendN = 0;
        recvN = 0;
        byte[] out = new byte[ct1.length + ct2.length];
        System.arraycopy(ct1, 0, out, 0, ct1.length);
        System.arraycopy(ct2, 0, out, ct1.length, ct2.length);
        return out;
    }

    // ---- transport (AD empty, big-endian nonce) ----
    public byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {
        byte[] ct = AesGcm.encrypt(sendKey, nonce(sendN), plaintext, new byte[0]);
        sendN++;
        return ct;
    }

    public byte[] decrypt(byte[] ciphertext) throws GeneralSecurityException {
        byte[] pt = AesGcm.decrypt(recvKey, nonce(recvN), ciphertext, new byte[0]);
        recvN++;
        return pt;
    }
}
