package dev.terminalsend.client.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * AES-256-GCM over a message payload (tech-spec §7.3). The AAD binds the ciphertext to its message id,
 * sender and recipient, so the server cannot replay it into another conversation or relabel the sender.
 */
public final class EnvelopeCipher {

    public static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private EnvelopeCipher() {
    }

    public record Sealed(byte[] nonce, byte[] ciphertext) {
    }

    public static Sealed seal(SecretKey key, UUID messageId, UUID senderId, UUID recipientId, byte[] plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(messageId, senderId, recipientId));
            return new Sealed(nonce, cipher.doFinal(plaintext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] open(SecretKey key, UUID messageId, UUID senderId, UUID recipientId, Sealed sealed)
            throws DecryptionException {
        if (sealed.nonce().length != NONCE_BYTES) {
            throw new DecryptionException("Invalid nonce length", null);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed.nonce()));
            cipher.updateAAD(aad(messageId, senderId, recipientId));
            return cipher.doFinal(sealed.ciphertext());
        } catch (AEADBadTagException e) {
            throw new DecryptionException("Message failed authentication", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] aad(UUID messageId, UUID senderId, UUID recipientId) {
        return ("v1|" + messageId + "|" + senderId + "|" + recipientId).getBytes(StandardCharsets.US_ASCII);
    }
}
