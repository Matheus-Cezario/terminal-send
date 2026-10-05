package dev.terminalsend.server.key;

import dev.terminalsend.protocol.KeyFingerprints;
import dev.terminalsend.protocol.rest.ErrorCode;
import dev.terminalsend.protocol.rest.KeyDtos.PublicKeyView;
import dev.terminalsend.server.common.ApiException;
import dev.terminalsend.server.connection.ConnectionRepository;
import dev.terminalsend.server.user.User;
import dev.terminalsend.server.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Base64;
import java.util.UUID;

@Service
public class KeyService {

    private final UserRepository users;
    private final ConnectionRepository connections;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public KeyService(UserRepository users, ConnectionRepository connections, JdbcClient jdbc,
                      ApplicationEventPublisher events, Clock clock) {
        this.users = users;
        this.connections = connections;
        this.jdbc = jdbc;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Publishes the caller's device key. Re-publishing the same key is a no-op; a different key means a new
     * device, so envelopes still queued for the old key can never be decrypted and are dropped.
     */
    @Transactional
    public void publish(UUID me, String base64Key) {
        byte[] raw = decode(base64Key);
        String fingerprint = KeyFingerprints.of(raw);
        User user = users.findById(me).orElseThrow();
        if (fingerprint.equals(user.getPublicKeyFingerprint())) {
            return;
        }
        boolean rotation = user.hasPublicKey();
        user.replacePublicKey(raw, fingerprint, clock.instant());
        if (rotation) {
            jdbc.sql("delete from pending_messages where recipient_id = :me").param("me", me).update();
            events.publishEvent(new KeyChanged(me, fingerprint));
        }
    }

    /** Only the owner and accepted connections may read a key; everyone else gets the same 404. */
    @Transactional(readOnly = true)
    public PublicKeyView get(UUID me, UUID userId) {
        boolean allowed = me.equals(userId) || connections.areConnected(me, userId);
        return users.findById(userId)
                .filter(u -> allowed && u.hasPublicKey())
                .map(u -> new PublicKeyView(Base64.getEncoder().encodeToString(u.getPublicKey()),
                        u.getPublicKeyFingerprint(), u.getKeyUpdatedAt()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ErrorCode.KEY_NOT_FOUND, "Key not found"));
    }

    private static byte[] decode(String base64Key) {
        try {
            byte[] raw = base64Key == null ? new byte[0] : Base64.getDecoder().decode(base64Key);
            if (raw.length == KeyFingerprints.PUBLIC_KEY_BYTES) {
                return raw;
            }
        } catch (IllegalArgumentException ignored) {
            // falls through to the validation error
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "publicKey must be a Base64 raw X25519 key (32 bytes)");
    }
}
