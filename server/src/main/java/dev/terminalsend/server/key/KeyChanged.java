package dev.terminalsend.server.key;

import java.util.UUID;

/** A user published a different identity key; connected peers must re-check its fingerprint (TOFU). */
public record KeyChanged(UUID userId, String fingerprint) {
}
