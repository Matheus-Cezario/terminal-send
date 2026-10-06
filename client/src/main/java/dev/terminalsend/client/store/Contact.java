package dev.terminalsend.client.store;

import dev.terminalsend.protocol.rest.ConnectionDtos.Direction;
import dev.terminalsend.protocol.rest.ConnectionDtos.Status;

import java.util.UUID;

/**
 * A peer as this device knows it. {@code fingerprint} is the key we trust (TOFU); {@code pendingFingerprint}
 * is a newer key the server announced that the user has not accepted yet.
 */
public record Contact(UUID userId, String email, String handle, UUID connectionId, Status status,
                      Direction direction, String fingerprint, String pendingFingerprint, boolean verified) {

    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }

    public boolean isIncomingInvite() {
        return status == Status.PENDING && direction == Direction.INCOMING;
    }

    public boolean keyChanged() {
        return pendingFingerprint != null;
    }

    public String displayName() {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
