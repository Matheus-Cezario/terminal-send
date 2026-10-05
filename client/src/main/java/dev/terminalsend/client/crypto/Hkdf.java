package dev.terminalsend.client.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;

/** HKDF-SHA256 (RFC 5869). {@code javax.crypto.KDF} only exists from JDK 24, and we target 21. */
public final class Hkdf {

    private static final int HASH_LEN = 32;

    private Hkdf() {
    }

    public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
        if (length <= 0 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("Invalid HKDF output length: " + length);
        }
        try {
            byte[] prk = hmac(salt == null || salt.length == 0 ? new byte[HASH_LEN] : salt, ikm);
            ByteArrayOutputStream okm = new ByteArrayOutputStream(length);
            byte[] previous = new byte[0];
            for (int counter = 1; okm.size() < length; counter++) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(prk, "HmacSHA256"));
                mac.update(previous);
                mac.update(info);
                mac.update((byte) counter);
                previous = mac.doFinal();
                okm.write(previous, 0, Math.min(previous.length, length - okm.size()));
            }
            return okm.toByteArray();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
