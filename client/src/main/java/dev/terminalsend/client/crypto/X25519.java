package dev.terminalsend.client.crypto;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import javax.crypto.KeyAgreement;

/**
 * X25519 with keys in their raw RFC 7748 form (32 little-endian bytes), which is what the server stores
 * and fingerprints. The JDK only exposes X.509/PKCS#8 encodings, hence the conversions here.
 */
public final class X25519 {

    public static final int KEY_BYTES = 32;

    private X25519() {
    }

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] encodePublic(XECPublicKey key) {
        return toLittleEndian(key.getU());
    }

    public static XECPublicKey decodePublic(byte[] raw) {
        requireLength(raw);
        byte[] masked = raw.clone();
        masked[KEY_BYTES - 1] &= 0x7f; // RFC 7748 §5: ignore the most significant bit of the u-coordinate
        try {
            return (XECPublicKey) KeyFactory.getInstance("X25519")
                    .generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, fromLittleEndian(masked)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid X25519 public key", e);
        }
    }

    public static byte[] encodePrivate(XECPrivateKey key) {
        return key.getScalar().orElseThrow(() -> new IllegalStateException("Private scalar not extractable"));
    }

    public static XECPrivateKey decodePrivate(byte[] raw) {
        requireLength(raw);
        try {
            return (XECPrivateKey) KeyFactory.getInstance("X25519")
                    .generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, raw.clone()));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid X25519 private key", e);
        }
    }

    /** Raw shared secret; rejects low-order peer keys that would force an all-zero secret. */
    public static byte[] agree(XECPrivateKey mine, XECPublicKey theirs) throws InvalidKeyException {
        byte[] shared;
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("X25519");
            agreement.init(mine);
            agreement.doPhase(theirs, true);
            shared = agreement.generateSecret();
        } catch (InvalidKeyException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        if (MessageDigest.isEqual(shared, new byte[shared.length])) {
            throw new InvalidKeyException("Peer public key has small order");
        }
        return shared;
    }

    private static byte[] toLittleEndian(BigInteger value) {
        byte[] bigEndian = value.toByteArray();
        byte[] out = new byte[KEY_BYTES];
        for (int i = 0; i < Math.min(KEY_BYTES, bigEndian.length); i++) {
            out[i] = bigEndian[bigEndian.length - 1 - i];
        }
        return out;
    }

    private static BigInteger fromLittleEndian(byte[] raw) {
        byte[] bigEndian = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            bigEndian[i] = raw[raw.length - 1 - i];
        }
        return new BigInteger(1, bigEndian);
    }

    private static void requireLength(byte[] raw) {
        if (raw == null || raw.length != KEY_BYTES) {
            throw new IllegalArgumentException("X25519 keys have 32 bytes, got " + (raw == null ? null : raw.length));
        }
    }

}
