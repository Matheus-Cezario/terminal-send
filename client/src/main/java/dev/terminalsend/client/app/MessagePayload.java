package dev.terminalsend.client.app;

import java.time.Instant;

/** The plaintext inside an envelope (tech-spec §7.3). {@code sentAt} here is authenticated, unlike the frame's. */
public record MessagePayload(String body, Instant sentAt) {
}
