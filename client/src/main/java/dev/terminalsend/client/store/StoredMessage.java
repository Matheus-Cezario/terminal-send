package dev.terminalsend.client.store;

import java.time.Instant;
import java.util.UUID;

public record StoredMessage(UUID id, UUID contactId, Direction direction, String body, Instant sentAt,
                            Instant receivedAt, State state) {

    public enum Direction { IN, OUT }

    /** Outgoing: QUEUED → SENT (✓) → DELIVERED (✓✓), or FAILED. Incoming messages are RECEIVED. */
    public enum State { QUEUED, SENT, DELIVERED, FAILED, RECEIVED }

    public StoredMessage withState(State newState) {
        return new StoredMessage(id, contactId, direction, body, sentAt, receivedAt, newState);
    }
}
