package dev.terminalsend.server.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PendingMessageRepository extends JpaRepository<PendingMessage, UUID> {

    /** Idempotent insert: client retries reuse the same message id. Returns 0 if the id already exists. */
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
            insert into pending_messages
                (id, sender_id, recipient_id, recipient_key_fp, nonce, ciphertext, sent_at, created_at, expires_at)
            values (:id, :senderId, :recipientId, :recipientKeyFp, :nonce, :ciphertext, :sentAt, :createdAt, :expiresAt)
            on conflict (id) do nothing
            """)
    int insertIfAbsent(UUID id, UUID senderId, UUID recipientId, String recipientKeyFp, byte[] nonce,
                       byte[] ciphertext, Instant sentAt, Instant createdAt, Instant expiresAt);

    @Query("""
            select m from PendingMessage m
            where m.recipientId = :recipientId and m.expiresAt > :now
            order by m.createdAt
            """)
    List<PendingMessage> findDeliverable(UUID recipientId, Instant now);

    /** Deletes acknowledged envelopes, scoped to the recipient, and returns who sent each one. */
    @Modifying
    @Transactional
    @Query(nativeQuery = true, value = """
            delete from pending_messages where recipient_id = :recipientId and id in (:ids)
            returning id, sender_id
            """)
    List<Object[]> deleteAcknowledged(UUID recipientId, Collection<UUID> ids);

    @Modifying
    @Transactional
    @Query("delete from PendingMessage m where m.expiresAt <= :now")
    int deleteExpired(Instant now);
}
