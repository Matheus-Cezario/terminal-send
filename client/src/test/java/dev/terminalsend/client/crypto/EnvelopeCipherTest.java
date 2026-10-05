package dev.terminalsend.client.crypto;

import dev.terminalsend.client.crypto.EnvelopeCipher.Sealed;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvelopeCipherTest {

    private final UUID anaId = UUID.randomUUID();
    private final UUID brunoId = UUID.randomUUID();
    private final KeyPair ana = X25519.generate();
    private final KeyPair bruno = X25519.generate();

    @Test
    void bothSidesDeriveTheSameKeyAndExchangeMessages() throws Exception {
        SecretKey anaKey = key(ana, anaId, bruno, brunoId);
        SecretKey brunoKey = key(bruno, brunoId, ana, anaId);
        assertThat(anaKey.getEncoded()).isEqualTo(brunoKey.getEncoded());

        UUID messageId = UUID.randomUUID();
        Sealed sealed = EnvelopeCipher.seal(anaKey, messageId, anaId, brunoId, utf8("oi, tudo bem? 👋"));

        assertThat(new String(EnvelopeCipher.open(brunoKey, messageId, anaId, brunoId, sealed),
                StandardCharsets.UTF_8)).isEqualTo("oi, tudo bem? 👋");
    }

    @Test
    void aThirdPartyDerivesADifferentKey() throws Exception {
        KeyPair carla = X25519.generate();
        UUID carlaId = UUID.randomUUID();

        assertThat(key(carla, carlaId, ana, anaId).getEncoded())
                .isNotEqualTo(key(bruno, brunoId, ana, anaId).getEncoded());
    }

    @Test
    void rejectsTamperedCiphertext() throws Exception {
        SecretKey key = key(ana, anaId, bruno, brunoId);
        UUID messageId = UUID.randomUUID();
        Sealed sealed = EnvelopeCipher.seal(key, messageId, anaId, brunoId, utf8("hello"));
        sealed.ciphertext()[0] ^= 1;

        assertThatThrownBy(() -> EnvelopeCipher.open(key, messageId, anaId, brunoId, sealed))
                .isInstanceOf(DecryptionException.class);
    }

    @Test
    void rejectsEnvelopeReplayedWithDifferentMetadata() throws Exception {
        SecretKey key = key(ana, anaId, bruno, brunoId);
        UUID messageId = UUID.randomUUID();
        Sealed sealed = EnvelopeCipher.seal(key, messageId, anaId, brunoId, utf8("hello"));

        assertThatThrownBy(() -> EnvelopeCipher.open(key, UUID.randomUUID(), anaId, brunoId, sealed))
                .as("different message id").isInstanceOf(DecryptionException.class);
        assertThatThrownBy(() -> EnvelopeCipher.open(key, messageId, brunoId, anaId, sealed))
                .as("sender and recipient swapped").isInstanceOf(DecryptionException.class);
    }

    @Test
    void usesFreshNonces() throws Exception {
        SecretKey key = key(ana, anaId, bruno, brunoId);

        Sealed first = EnvelopeCipher.seal(key, UUID.randomUUID(), anaId, brunoId, utf8("same"));
        Sealed second = EnvelopeCipher.seal(key, UUID.randomUUID(), anaId, brunoId, utf8("same"));

        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    private static SecretKey key(KeyPair mine, UUID myId, KeyPair theirs, UUID theirId) throws Exception {
        return PairKeys.derive((XECPrivateKey) mine.getPrivate(), myId, (XECPublicKey) theirs.getPublic(), theirId);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
