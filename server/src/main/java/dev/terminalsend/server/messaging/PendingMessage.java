package dev.terminalsend.server.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** An encrypted envelope waiting for the recipient's ACK. The server never sees its plaintext. */
@Entity
@Table(name = "pending_messages")
public class PendingMessage {

    @Id
    private UUID id;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(name = "recipient_key_fp", nullable = false, length = 64)
    private String recipientKeyFp;

    @Column(nullable = false)
    private byte[] nonce;

    @Column(nullable = false)
    private byte[] ciphertext;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected PendingMessage() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public String getRecipientKeyFp() {
        return recipientKeyFp;
    }

    public byte[] getNonce() {
        return nonce;
    }

    public byte[] getCiphertext() {
        return ciphertext;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
