package dev.terminalsend.client.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

class IdentityKeyFileTest {

    private static final char[] PASSWORD = "correct horse battery".toCharArray();

    @TempDir
    Path home;

    private final IdentityKeyFile store = new IdentityKeyFile(1_000);

    @Test
    void savesAndLoadsTheSameKeyPair() throws Exception {
        KeyPair identity = X25519.generate();
        Path file = home.resolve("ts-7KQ2MX/identity.key");

        store.save(file, identity, PASSWORD);
        KeyPair loaded = store.load(file, PASSWORD);

        assertThat(X25519.encodePublic((XECPublicKey) loaded.getPublic()))
                .isEqualTo(X25519.encodePublic((XECPublicKey) identity.getPublic()));
        assertThat(X25519.encodePrivate((XECPrivateKey) loaded.getPrivate()))
                .isEqualTo(X25519.encodePrivate((XECPrivateKey) identity.getPrivate()));
    }

    @Test
    void privateKeyIsNotStoredInClear() throws Exception {
        KeyPair identity = X25519.generate();
        Path file = home.resolve("identity.key");

        store.save(file, identity, PASSWORD);

        String content = Files.readString(file);
        String rawPrivate = java.util.Base64.getEncoder()
                .encodeToString(X25519.encodePrivate((XECPrivateKey) identity.getPrivate()));
        assertThat(content).doesNotContain(rawPrivate).contains("\"kdf\":\"PBKDF2WithHmacSHA256\"");
    }

    @Test
    void wrongPasswordFails() throws Exception {
        Path file = home.resolve("identity.key");
        store.save(file, X25519.generate(), PASSWORD);

        assertThatThrownBy(() -> store.load(file, "wrong password".toCharArray()))
                .isInstanceOf(DecryptionException.class);
    }

    @Test
    void swappingThePublicKeyIsDetected() throws Exception {
        Path file = home.resolve("identity.key");
        store.save(file, X25519.generate(), PASSWORD);
        String otherPublic = java.util.Base64.getEncoder()
                .encodeToString(X25519.encodePublic((XECPublicKey) X25519.generate().getPublic()));

        String content = Files.readString(file).replaceFirst("\"publicKey\":\"[^\"]+\"",
                "\"publicKey\":\"" + otherPublic + "\"");
        Files.writeString(file, content);

        assertThatThrownBy(() -> store.load(file, PASSWORD)).isInstanceOf(DecryptionException.class);
    }

    @Test
    void fileAndDirectoryAreOwnerOnly() throws Exception {
        assumeThat(FileSystems.getDefault().supportedFileAttributeViews()).contains("posix");
        Path file = home.resolve("ts-7KQ2MX/identity.key");

        store.save(file, X25519.generate(), PASSWORD);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())))
                .isEqualTo("rwx------");
        try (var files = Files.list(file.getParent())) {
            assertThat(files).containsExactly(file);
        }
    }
}
