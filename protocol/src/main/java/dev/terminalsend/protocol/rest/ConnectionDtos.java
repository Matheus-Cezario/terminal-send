package dev.terminalsend.protocol.rest;

import java.time.Instant;
import java.util.UUID;

/** Bodies for {@code /api/v1/connections}. */
public final class ConnectionDtos {

    private ConnectionDtos() {
    }

    public enum Status { PENDING, ACCEPTED, REJECTED }

    public enum Direction { INCOMING, OUTGOING }

    /** {@code target} is either an email or a handle ({@code ts-XXXXXX}). */
    public record InviteRequest(String target) {
    }

    public record PeerView(UUID id, String email, String handle) {
    }

    public record ConnectionView(UUID id, PeerView peer, Status status, Direction direction, Instant createdAt) {
    }
}
