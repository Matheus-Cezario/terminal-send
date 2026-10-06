package dev.terminalsend.server.messaging;

import dev.terminalsend.protocol.rest.ErrorCode;
import dev.terminalsend.protocol.ws.ClientFrame.MessageAck;
import dev.terminalsend.protocol.ws.ClientFrame.MessageSend;
import dev.terminalsend.protocol.ws.ServerFrame.MessageAccepted;
import dev.terminalsend.protocol.ws.ServerFrame.MessageDeliver;
import dev.terminalsend.protocol.ws.ServerFrame.MessageDelivered;
import dev.terminalsend.protocol.ws.ServerFrame.MessageRejected;
import dev.terminalsend.server.config.TerminalSendProperties;
import dev.terminalsend.server.connection.ConnectionRepository;
import dev.terminalsend.server.user.User;
import dev.terminalsend.server.user.UserRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Store-and-forward relay (tech-spec RF5). Every envelope is persisted before it is acknowledged to the
 * sender, whether or not the recipient is online, so the recipient's ACK is the single path that deletes it.
 */
@Service
public class RelayService {

    private static final int NONCE_BYTES = 12;

    private final PendingMessageRepository pending;
    private final ConnectionRepository connections;
    private final UserRepository users;
    private final PresenceRegistry presence;
    private final SendRateLimiter rateLimiter;
    private final TerminalSendProperties.Messaging config;
    private final Clock clock;

    public RelayService(PendingMessageRepository pending, ConnectionRepository connections, UserRepository users,
                        PresenceRegistry presence, SendRateLimiter rateLimiter, TerminalSendProperties props,
                        Clock clock) {
        this.pending = pending;
        this.connections = connections;
        this.users = users;
        this.presence = presence;
        this.rateLimiter = rateLimiter;
        this.config = props.messaging();
        this.clock = clock;
    }

    public void send(UUID sender, MessageSend message) {
        Instant now = clock.instant();
        Optional<ErrorCode> problem = validate(sender, message);
        if (problem.isPresent()) {
            presence.send(sender, new MessageRejected(message.id(), problem.get()));
            return;
        }
        Base64.Decoder b64 = Base64.getDecoder();
        int inserted = pending.insertIfAbsent(message.id(), sender, message.to(), message.recipientKeyFp(),
                b64.decode(message.nonce()), b64.decode(message.ciphertext()), message.sentAt(), now,
                now.plus(config.pendingTtl()));
        if (inserted == 0 && !isRetryOfOwnMessage(sender, message)) {
            presence.send(sender, new MessageRejected(message.id(), ErrorCode.VALIDATION_FAILED));
            return;
        }
        presence.send(sender, new MessageAccepted(message.id()));
        presence.send(message.to(), new MessageDeliver(message.id(), sender, senderFingerprint(sender),
                message.nonce(), message.ciphertext(), message.sentAt()));
    }

    /** Only the recipient can acknowledge; the sender gets its double check mark if online. */
    public void ack(UUID recipient, MessageAck ack) {
        if (ack.ids() == null || ack.ids().isEmpty()) {
            return;
        }
        for (Object[] row : pending.deleteAcknowledged(recipient, ack.ids())) {
            presence.send((UUID) row[1], new MessageDelivered((UUID) row[0]));
        }
    }

    /** Called when a user connects: replays everything still waiting for them, oldest first. */
    public void flushPending(UUID recipient) {
        Base64.Encoder b64 = Base64.getEncoder();
        for (PendingMessage m : pending.findDeliverable(recipient, clock.instant())) {
            boolean sent = presence.send(recipient, new MessageDeliver(m.getId(), m.getSenderId(),
                    senderFingerprint(m.getSenderId()), b64.encodeToString(m.getNonce()),
                    b64.encodeToString(m.getCiphertext()), m.getSentAt()));
            if (!sent) {
                return;
            }
        }
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void purgeExpired() {
        pending.deleteExpired(clock.instant());
    }

    private Optional<ErrorCode> validate(UUID sender, MessageSend m) {
        if (m.id() == null || m.to() == null || m.sentAt() == null || m.recipientKeyFp() == null
                || m.nonce() == null || m.ciphertext() == null) {
            return Optional.of(ErrorCode.VALIDATION_FAILED);
        }
        if (!rateLimiter.tryAcquire(sender)) {
            return Optional.of(ErrorCode.RATE_LIMITED);
        }
        int ciphertextBytes;
        try {
            if (Base64.getDecoder().decode(m.nonce()).length != NONCE_BYTES) {
                return Optional.of(ErrorCode.VALIDATION_FAILED);
            }
            ciphertextBytes = Base64.getDecoder().decode(m.ciphertext()).length;
        } catch (IllegalArgumentException e) {
            return Optional.of(ErrorCode.VALIDATION_FAILED);
        }
        if (ciphertextBytes > config.maxCiphertextBytes()) {
            return Optional.of(ErrorCode.TOO_LARGE);
        }
        if (!connections.areConnected(sender, m.to())) {
            return Optional.of(ErrorCode.NOT_CONNECTED);
        }
        String currentFp = users.findById(m.to()).map(User::getPublicKeyFingerprint).orElse(null);
        if (!m.recipientKeyFp().equals(currentFp)) {
            return Optional.of(ErrorCode.KEY_MISMATCH);
        }
        return Optional.empty();
    }

    private boolean isRetryOfOwnMessage(UUID sender, MessageSend message) {
        return pending.findById(message.id())
                .map(existing -> existing.getSenderId().equals(sender) && existing.getRecipientId().equals(message.to()))
                .orElse(false);
    }

    private String senderFingerprint(UUID sender) {
        return users.findById(sender).map(User::getPublicKeyFingerprint).filter(Objects::nonNull).orElse("");
    }

}
