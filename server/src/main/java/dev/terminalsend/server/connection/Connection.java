package dev.terminalsend.server.connection;

import dev.terminalsend.protocol.rest.ConnectionDtos.Status;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One row per unordered pair of users (enforced by a unique index on least/greatest ids). */
@Entity
@Table(name = "connections")
public class Connection {

    @Id
    private UUID id;

    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @Column(name = "addressee_id", nullable = false)
    private UUID addresseeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    protected Connection() {
    }

    public Connection(UUID id, UUID requesterId, UUID addresseeId, Instant createdAt) {
        this.id = id;
        this.requesterId = requesterId;
        this.addresseeId = addresseeId;
        this.status = Status.PENDING;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRequesterId() {
        return requesterId;
    }

    public UUID getAddresseeId() {
        return addresseeId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID peerOf(UUID userId) {
        return userId.equals(requesterId) ? addresseeId : requesterId;
    }

    public boolean isAddressee(UUID userId) {
        return addresseeId.equals(userId);
    }

    /**
     * A rejection is silent: the requester keeps seeing a pending invite, while the addressee no longer sees it.
     */
    public boolean isVisibleTo(UUID userId) {
        return status != Status.REJECTED || requesterId.equals(userId);
    }

    /** Status as {@code userId} should see it (hides rejections from the requester). */
    public Status statusFor(UUID userId) {
        return status == Status.REJECTED ? Status.PENDING : status;
    }

    public void accept(Instant at) {
        status = Status.ACCEPTED;
        respondedAt = at;
    }

    public void reject(Instant at) {
        status = Status.REJECTED;
        respondedAt = at;
    }

    /** Someone who rejected an invite changed their mind and is now inviting back. */
    public void reopenAsInviteFrom(UUID newRequesterId, Instant at) {
        addresseeId = peerOf(newRequesterId);
        requesterId = newRequesterId;
        status = Status.PENDING;
        createdAt = at;
        respondedAt = null;
    }
}
