package dev.terminalsend.server.messaging;

import dev.terminalsend.protocol.ws.ServerFrame;
import dev.terminalsend.server.connection.ConnectionEvents.ConnectionAccepted;
import dev.terminalsend.server.connection.ConnectionEvents.ConnectionRemoved;
import dev.terminalsend.server.connection.ConnectionEvents.ConnectionRequested;
import dev.terminalsend.server.connection.ConnectionRepository;
import dev.terminalsend.server.connection.ConnectionService;
import dev.terminalsend.server.key.KeyChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Turns committed domain events into WebSocket frames for whoever is online. */
@Component
class RealtimeNotifier {

    private final PresenceRegistry presence;
    private final ConnectionService connectionService;
    private final ConnectionRepository connections;

    RealtimeNotifier(PresenceRegistry presence, ConnectionService connectionService,
                     ConnectionRepository connections) {
        this.presence = presence;
        this.connectionService = connectionService;
        this.connections = connections;
    }

    @TransactionalEventListener
    void on(ConnectionRequested event) {
        if (presence.isOnline(event.notifyUserId())) {
            connectionService.viewFor(event.notifyUserId(), event.connectionId())
                    .ifPresent(view -> presence.send(event.notifyUserId(), new ServerFrame.ConnectionRequested(view)));
        }
    }

    @TransactionalEventListener
    void on(ConnectionAccepted event) {
        if (presence.isOnline(event.notifyUserId())) {
            connectionService.viewFor(event.notifyUserId(), event.connectionId())
                    .ifPresent(view -> presence.send(event.notifyUserId(), new ServerFrame.ConnectionAccepted(view)));
        }
    }

    @TransactionalEventListener
    void on(ConnectionRemoved event) {
        presence.send(event.notifyUserId(), new ServerFrame.ConnectionRemoved(event.connectionId()));
    }

    @TransactionalEventListener
    void on(KeyChanged event) {
        ServerFrame frame = new ServerFrame.KeyChanged(event.userId(), event.fingerprint());
        connections.findAcceptedPeerIds(event.userId()).forEach(peer -> presence.send(peer, frame));
    }
}
