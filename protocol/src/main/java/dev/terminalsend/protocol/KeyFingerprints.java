package dev.terminalsend.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.StringJoiner;

/** Fingerprints of raw 32-byte X25519 public keys, computed identically by client and server. */
public final class KeyFingerprints {

    public static final int PUBLIC_KEY_BYTES = 32;

    private KeyFingerprints() {
    }

    /** Lowercase hex SHA-256 of the raw public key (64 chars); this is what travels in frames and APIs. */
    public static String of(byte[] rawPublicKey) {
        if (rawPublicKey.length != PUBLIC_KEY_BYTES) {
            throw new IllegalArgumentException("X25519 public keys have 32 bytes, got " + rawPublicKey.length);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawPublicKey));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Human-comparable form: first 128 bits as 8 groups of 4 hex chars, e.g. {@code 3f2a 91c0 ...}. */
    public static String display(String fingerprint) {
        StringJoiner groups = new StringJoiner(" ");
        for (int i = 0; i < 32; i += 4) {
            groups.add(fingerprint.substring(i, i + 4));
        }
        return groups.toString();
    }
}
