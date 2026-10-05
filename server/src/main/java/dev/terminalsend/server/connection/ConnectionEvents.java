package dev.terminalsend.server.connection;

import java.util.UUID;

/**
 * Published inside the connection transaction; the WebSocket layer (M5) turns them into frames after commit.
 * {@code notifyUserId} is the peer that should be told, never the user who acted.
 */
public final class ConnectionEvents {

    private ConnectionEvents() {
    }

    public record ConnectionRequested(UUID connectionId, UUID notifyUserId) {
    }

    public record ConnectionAccepted(UUID connectionId, UUID notifyUserId) {
    }

    public record ConnectionRemoved(UUID connectionId, UUID notifyUserId) {
    }
}
