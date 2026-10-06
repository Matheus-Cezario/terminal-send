package dev.terminalsend.client.app;

import dev.terminalsend.client.net.ApiClient;
import dev.terminalsend.client.net.ApiError;
import dev.terminalsend.client.net.RealtimeClient;
import dev.terminalsend.client.store.LocalStore;
import dev.terminalsend.protocol.rest.AuthDtos.UserView;
import dev.terminalsend.protocol.ws.ServerFrame;

/** Everything that exists while a user is logged in on this device. */
public final class Session implements AutoCloseable {

    private final UserView me;
    private final String fingerprint;
    private final ApiClient api;
    private final LocalStore store;
    private final ContactService contacts;
    private final ChatService chat;
    private final ChatEvents events;
    private RealtimeClient realtime;

    Session(UserView me, String fingerprint, ApiClient api, LocalStore store, ContactService contacts,
            ChatService chat, ChatEvents events) {
        this.me = me;
        this.fingerprint = fingerprint;
        this.api = api;
        this.store = store;
        this.contacts = contacts;
        this.chat = chat;
        this.events = events;
    }

    void attach(RealtimeClient realtime) {
        this.realtime = realtime;
        chat.useTransport(realtime::send);
    }

    /** Loads contacts and opens the realtime connection, which also flushes the outbox. */
    public void start() {
        try {
            contacts.sync();
        } catch (ApiError e) {
            events.notice("Sem conexão com o servidor; tentando de novo em segundo plano.");
        }
        realtime.start();
    }

    public UserView me() {
        return me;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public ContactService contacts() {
        return contacts;
    }

    public ChatService chat() {
        return chat;
    }

    public boolean isOnline() {
        return realtime != null && realtime.isConnected();
    }

    public void logout() {
        close();
        try {
            api.logout();
        } catch (ApiError ignored) {
            // the refresh token expires on its own
        }
    }

    @Override
    public void close() {
        if (realtime != null) {
            realtime.close();
        }
        store.close();
    }

    /** Routes realtime callbacks to the services; runs on the WebSocket's threads. */
    RealtimeClient.Listener realtimeListener() {
        return new RealtimeClient.Listener() {
            @Override
            public void onConnected() {
                events.connectionChanged(true);
                try {
                    contacts.sync();
                } catch (ApiError ignored) {
                    // next event will retry
                }
                chat.flushOutbox();
            }

            @Override
            public void onFrame(ServerFrame frame) {
                switch (frame) {
                    case ServerFrame.ConnectionRequested r -> contacts.sync();
                    case ServerFrame.ConnectionAccepted a -> contacts.sync();
                    case ServerFrame.ConnectionRemoved r -> contacts.sync();
                    case ServerFrame.KeyChanged k -> contacts.refreshKey(k.userId());
                    case ServerFrame.Error e -> events.notice("Servidor: " + e.message());
                    default -> chat.handle(frame);
                }
            }

            @Override
            public void onDisconnected() {
                events.connectionChanged(false);
            }

            @Override
            public void onReplaced() {
                events.sessionReplaced();
            }
        };
    }
}
