package dev.terminalsend.client.crypto;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.util.Arrays;
import java.util.Base64;

/**
 * Stores the device's X25519 identity at {@code ~/.terminal-send/<handle>/identity.key}, with the private key
 * encrypted under a key derived from the account password (PBKDF2-HMAC-SHA256 + AES-256-GCM, tech-spec RF3.2).
 */
public final class IdentityKeyFile {

    static final int DEFAULT_ITERATIONS = 600_000;
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final int VERSION = 1;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final int iterations;

    public IdentityKeyFile() {
        this(DEFAULT_ITERATIONS);
    }

    /** Lower iteration counts are only meant for tests. */
    IdentityKeyFile(int iterations) {
        this.iterations = iterations;
    }

    /** On-disk JSON. The public key is stored in clear and authenticated as AAD. */
    record Stored(int version, String kdf, int iterations, String salt, String nonce, String ciphertext,
                  String publicKey) {
    }

    public void save(Path file, KeyPair identity, char[] password) throws IOException {
        byte[] salt = random(16);
        byte[] nonce = random(EnvelopeCipher.NONCE_BYTES);
        byte[] rawPublic = X25519.encodePublic((XECPublicKey) identity.getPublic());
        byte[] rawPrivate = X25519.encodePrivate((XECPrivateKey) identity.getPrivate());
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(password, salt, iterations), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(rawPublic));
            ciphertext = cipher.doFinal(rawPrivate);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            Arrays.fill(rawPrivate, (byte) 0);
        }
        Base64.Encoder b64 = Base64.getEncoder();
        Stored stored = new Stored(VERSION, KDF, iterations, b64.encodeToString(salt), b64.encodeToString(nonce),
                b64.encodeToString(ciphertext), b64.encodeToString(rawPublic));
        writeOwnerOnly(file, JSON.writeValueAsBytes(stored));
    }

    /** @throws DecryptionException if the password is wrong or the file was tampered with */
    public KeyPair load(Path file, char[] password) throws IOException, DecryptionException {
        Stored stored;
        try {
            stored = JSON.readValue(Files.readAllBytes(file), Stored.class);
        } catch (JacksonException e) {
            throw new IOException("Corrupt identity file: " + file, e);
        }
        if (stored.version() != VERSION || !KDF.equals(stored.kdf())) {
            throw new IOException("Unsupported identity file format: " + file);
        }
        Base64.Decoder b64 = Base64.getDecoder();
        byte[] rawPublic = b64.decode(stored.publicKey());
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(password, b64.decode(stored.salt()), stored.iterations()),
                    new GCMParameterSpec(128, b64.decode(stored.nonce())));
            cipher.updateAAD(aad(rawPublic));
            byte[] rawPrivate = cipher.doFinal(b64.decode(stored.ciphertext()));
            try {
                return new KeyPair(X25519.decodePublic(rawPublic), X25519.decodePrivate(rawPrivate));
            } finally {
                Arrays.fill(rawPrivate, (byte) 0);
            }
        } catch (AEADBadTagException e) {
            throw new DecryptionException("Wrong password or tampered identity file", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SecretKey wrappingKey(char[] password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            byte[] key = SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] aad(byte[] rawPublic) {
        return ("terminal-send/identity/v1|" + Base64.getEncoder().encodeToString(rawPublic))
                .getBytes(StandardCharsets.US_ASCII);
    }

    /** Writes via a temp file in the same directory and an atomic move, with 0600/0700 permissions on POSIX. */
    static void writeOwnerOnly(Path file, byte[] content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (!Files.isDirectory(dir)) {
            if (posix) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(dir);
            }
        }
        Path tmp = posix
                ? Files.createTempFile(dir, ".identity", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(dir, ".identity", ".tmp");
        try {
            Files.write(tmp, content);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
