package dev.terminalsend.protocol.rest;

import java.time.Instant;

/** Bodies for {@code /api/v1/me/key} and {@code /api/v1/users/{id}/key}. Keys are Base64 X25519 (32 bytes). */
public final class KeyDtos {

    private KeyDtos() {
    }

    public record PublicKeyUpload(String publicKey) {
    }

    public record PublicKeyView(String publicKey, String fingerprint, Instant updatedAt) {
    }
}
