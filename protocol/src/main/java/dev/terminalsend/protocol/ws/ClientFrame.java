package dev.terminalsend.protocol.ws;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Frames sent from the client to the server over {@code /ws}. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClientFrame.MessageSend.class, name = "message.send"),
        @JsonSubTypes.Type(value = ClientFrame.MessageAck.class, name = "message.ack"),
})
public sealed interface ClientFrame {

    /** Max Base64 ciphertext length accepted by the server (64 KiB of raw bytes). */
    int MAX_CIPHERTEXT_BYTES = 64 * 1024;

    /** An end-to-end encrypted message. {@code nonce} and {@code ciphertext} are Base64. */
    record MessageSend(UUID id, UUID to, String recipientKeyFp, String nonce, String ciphertext, Instant sentAt)
            implements ClientFrame {
    }

    /** Confirms messages were persisted locally, so the server can delete them. */
    record MessageAck(List<UUID> ids) implements ClientFrame {
    }
}
