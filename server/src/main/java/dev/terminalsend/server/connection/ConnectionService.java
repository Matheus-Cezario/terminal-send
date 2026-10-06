package dev.terminalsend.server.connection;

import dev.terminalsend.protocol.Identifiers;
import dev.terminalsend.protocol.rest.ConnectionDtos.ConnectionView;
import dev.terminalsend.protocol.rest.ConnectionDtos.Direction;
import dev.terminalsend.protocol.rest.ConnectionDtos.PeerView;
import dev.terminalsend.protocol.rest.ConnectionDtos.Status;
import dev.terminalsend.protocol.rest.ErrorCode;
import dev.terminalsend.server.common.ApiException;
import dev.terminalsend.server.connection.ConnectionEvents.ConnectionAccepted;
import dev.terminalsend.server.connection.ConnectionEvents.ConnectionRemoved;
import dev.terminalsend.server.connection.ConnectionEvents.ConnectionRequested;
import dev.terminalsend.server.user.User;
import dev.terminalsend.server.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ConnectionService {

    private final ConnectionRepository connections;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ConnectionService(ConnectionRepository connections, UserRepository users,
                             ApplicationEventPublisher events, Clock clock) {
        this.connections = connections;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Sends an invite to an email or handle. Outcomes the caller must not learn about (unknown or unverified
     * target, inviting yourself, an invite that was silently rejected) all look the same: nothing visible happens.
     */
    @Transactional
    public void invite(UUID me, String target) {
        Optional<User> peer = resolveTarget(target).filter(u -> !u.getId().equals(me));
        if (peer.isEmpty()) {
            return;
        }
        UUID peerId = peer.get().getId();
        Optional<Connection> existing = connections.findBetween(me, peerId);
        if (existing.isEmpty()) {
            Connection created = connections.save(new Connection(UUID.randomUUID(), me, peerId, clock.instant()));
            events.publishEvent(new ConnectionRequested(created.getId(), peerId));
            return;
        }

        Connection connection = existing.get();
        boolean theyInvitedMe = connection.isAddressee(me);
        switch (connection.getStatus()) {
            case ACCEPTED -> { }
            case PENDING -> {
                // Both sides want it: treat the second invite as an acceptance.
                if (theyInvitedMe) {
                    connection.accept(clock.instant());
                    events.publishEvent(new ConnectionAccepted(connection.getId(), peerId));
                }
            }
            case REJECTED -> {
                // Only the person who rejected can reopen; the rejected requester keeps a silent "pending".
                if (theyInvitedMe) {
                    connection.reopenAsInviteFrom(me, clock.instant());
                    events.publishEvent(new ConnectionRequested(connection.getId(), peerId));
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public List<ConnectionView> list(UUID me, Status statusFilter) {
        List<Connection> visible = connections.findAllInvolving(me).stream()
                .filter(c -> c.isVisibleTo(me))
                .filter(c -> statusFilter == null || c.statusFor(me) == statusFilter)
                .toList();
        Map<UUID, User> peers = users.findAllById(visible.stream().map(c -> c.peerOf(me)).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return visible.stream().map(c -> view(c, me, peers.get(c.peerOf(me)))).toList();
    }

    /** Fresh view of a connection from {@code me}'s side; runs in its own transaction for after-commit callers. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ConnectionView> viewFor(UUID me, UUID connectionId) {
        return connections.findById(connectionId)
                .filter(c -> c.getRequesterId().equals(me) || c.getAddresseeId().equals(me))
                .filter(c -> c.isVisibleTo(me))
                .flatMap(c -> users.findById(c.peerOf(me)).map(peer -> view(c, me, peer)));
    }

    @Transactional
    public ConnectionView accept(UUID me, UUID connectionId) {
        Connection connection = incomingPending(me, connectionId);
        connection.accept(clock.instant());
        UUID requester = connection.getRequesterId();
        events.publishEvent(new ConnectionAccepted(connection.getId(), requester));
        return view(connection, me, users.findById(requester).orElseThrow());
    }

    @Transactional
    public void reject(UUID me, UUID connectionId) {
        incomingPending(me, connectionId).reject(clock.instant());
    }

    /** Cancels an outgoing invite or removes an accepted contact. */
    @Transactional
    public void remove(UUID me, UUID connectionId) {
        Connection connection = visibleTo(me, connectionId);
        UUID peer = connection.peerOf(me);
        boolean peerSeesIt = connection.isVisibleTo(peer);
        connections.delete(connection);
        if (peerSeesIt) {
            events.publishEvent(new ConnectionRemoved(connection.getId(), peer));
        }
    }

    private Connection incomingPending(UUID me, UUID connectionId) {
        Connection connection = visibleTo(me, connectionId);
        if (!connection.isAddressee(me) || connection.getStatus() != Status.PENDING) {
            throw notFound();
        }
        return connection;
    }

    /** Anything the caller can't see is reported as missing, never as forbidden. */
    private Connection visibleTo(UUID me, UUID connectionId) {
        return connections.findById(connectionId)
                .filter(c -> c.getRequesterId().equals(me) || c.getAddresseeId().equals(me))
                .filter(c -> c.isVisibleTo(me))
                .orElseThrow(ConnectionService::notFound);
    }

    private Optional<User> resolveTarget(String target) {
        if (Identifiers.isEmail(target)) {
            return users.findByEmail(Identifiers.normalizeEmail(target)).filter(User::isEmailVerified);
        }
        if (Identifiers.isHandle(target)) {
            return users.findByHandle(Identifiers.normalizeHandle(target)).filter(User::isEmailVerified);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Target must be an email or a handle like ts-7KQ2MX");
    }

    private static ConnectionView view(Connection c, UUID me, User peer) {
        Direction direction = c.isAddressee(me) ? Direction.INCOMING : Direction.OUTGOING;
        return new ConnectionView(c.getId(), new PeerView(peer.getId(), peer.getEmail(), peer.getHandle()),
                c.statusFor(me), direction, c.getCreatedAt());
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Connection not found");
    }
}
