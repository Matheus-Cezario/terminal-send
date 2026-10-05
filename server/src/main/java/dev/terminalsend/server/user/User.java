package dev.terminalsend.server.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, columnDefinition = "citext")
    private String email;

    @Column(nullable = false, unique = true, length = 9)
    private String handle;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "public_key")
    private byte[] publicKey;

    @Column(name = "public_key_fp", length = 64)
    private String publicKeyFingerprint;

    @Column(name = "key_updated_at")
    private Instant keyUpdatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() {
    }

    public User(UUID id, String email, String handle, String passwordHash, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.handle = handle;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getHandle() {
        return handle;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public void markEmailVerified(Instant at) {
        this.emailVerifiedAt = at;
    }

    public byte[] getPublicKey() {
        return publicKey;
    }

    public String getPublicKeyFingerprint() {
        return publicKeyFingerprint;
    }

    public boolean hasPublicKey() {
        return publicKey != null;
    }

    public void replacePublicKey(byte[] rawKey, String fingerprint, Instant at) {
        this.publicKey = rawKey.clone();
        this.publicKeyFingerprint = fingerprint;
        this.keyUpdatedAt = at;
    }

    public Instant getKeyUpdatedAt() {
        return keyUpdatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
