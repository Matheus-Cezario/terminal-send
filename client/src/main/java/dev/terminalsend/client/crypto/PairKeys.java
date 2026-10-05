package dev.terminalsend.client.crypto;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.util.Arrays;
import java.util.UUID;

/**
 * Derives the AES key shared by two users (tech-spec §7.2). Both sides compute the same key because the
 * salt orders the two user ids the same way regardless of who is deriving.
 */
public final class PairKeys {

    static final byte[] INFO = "terminal-send/v1/msg".getBytes(StandardCharsets.US_ASCII);

    private PairKeys() {
    }

    public static SecretKey derive(XECPrivateKey mine, UUID myId, XECPublicKey theirs, UUID theirId)
            throws InvalidKeyException {
        byte[] shared = X25519.agree(mine, theirs);
        try {
            return new SecretKeySpec(Hkdf.derive(shared, salt(myId, theirId), INFO, 32), "AES");
        } finally {
            Arrays.fill(shared, (byte) 0);
        }
    }

    /** SHA-256 of both 16-byte big-endian UUIDs, smaller one (unsigned byte order) first. */
    static byte[] salt(UUID a, UUID b) {
        byte[] first = bytes(a);
        byte[] second = bytes(b);
        if (Arrays.compareUnsigned(first, second) > 0) {
            byte[] tmp = first;
            first = second;
            second = tmp;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(first);
            sha.update(second);
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .array();
    }
}
