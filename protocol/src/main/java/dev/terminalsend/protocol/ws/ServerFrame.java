package dev.terminalsend.protocol.ws;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.terminalsend.protocol.rest.ConnectionDtos.ConnectionView;
import dev.terminalsend.protocol.rest.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/** Frames pushed from the server to the client over {@code /ws}. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ServerFrame.MessageAccepted.class, name = "message.accepted"),
        @JsonSubTypes.Type(value = ServerFrame.MessageDeliver.class, name = "message.deliver"),
        @JsonSubTypes.Type(value = ServerFrame.MessageDelivered.class, name = "message.delivered"),
        @JsonSubTypes.Type(value = ServerFrame.MessageRejected.class, name = "message.rejected"),
        @JsonSubTypes.Type(value = ServerFrame.ConnectionRequested.class, name = "connection.requested"),
        @JsonSubTypes.Type(value = ServerFrame.ConnectionAccepted.class, name = "connection.accepted"),
        @JsonSubTypes.Type(value = ServerFrame.ConnectionRemoved.class, name = "connection.removed"),
        @JsonSubTypes.Type(value = ServerFrame.KeyChanged.class, name = "key.changed"),
        @JsonSubTypes.Type(value = ServerFrame.Error.class, name = "error"),
})
public sealed interface ServerFrame {

    /** WS close code: a newer session for the same user replaced this one. */
    int CLOSE_SESSION_REPLACED = 4001;
    /** WS close code: access token expired; refresh and reconnect. */
    int CLOSE_TOKEN_EXPIRED = 4401;

    /** The server persisted (and possibly delivered) the message: one check mark. */
    record MessageAccepted(UUID id) implements ServerFrame {
    }

    record MessageDeliver(UUID id, UUID from, String senderKeyFp, String nonce, String ciphertext, Instant sentAt)
            implements ServerFrame {
    }

    /** The recipient acknowledged the message: two check marks. */
    record MessageDelivered(UUID id) implements ServerFrame {
    }

    record MessageRejected(UUID id, ErrorCode code) implements ServerFrame {
    }

    record ConnectionRequested(ConnectionView connection) implements ServerFrame {
    }

    record ConnectionAccepted(ConnectionView connection) implements ServerFrame {
    }

    record ConnectionRemoved(UUID connectionId) implements ServerFrame {
    }

    record KeyChanged(UUID userId, String fingerprint) implements ServerFrame {
    }

    record Error(ErrorCode code, String message) implements ServerFrame {
    }
}
