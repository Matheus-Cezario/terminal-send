package dev.terminalsend.server.ws;

import dev.terminalsend.protocol.rest.ErrorCode;
import dev.terminalsend.protocol.ws.ClientFrame;
import dev.terminalsend.protocol.ws.ServerFrame;
import dev.terminalsend.server.messaging.PresenceRegistry;
import dev.terminalsend.server.messaging.RelayService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** JSON frames over {@code /ws} (tech-spec §6). Business rules live in {@link RelayService}. */
@Component
class WsHandler extends TextWebSocketHandler {

    static final CloseStatus TOKEN_EXPIRED = new CloseStatus(ServerFrame.CLOSE_TOKEN_EXPIRED, "Access token expired");
    private static final String EXPIRY_TASK = "expiryTask";

    private final PresenceRegistry presence;
    private final RelayService relay;
    private final JsonMapper json;
    private final Clock clock;
    private final ScheduledExecutorService expiries =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("ws-token-expiry").factory());

    WsHandler(PresenceRegistry presence, RelayService relay, JsonMapper json, Clock clock) {
        this.presence = presence;
        this.relay = relay;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID userId = userId(session);
        scheduleTokenExpiry(session);
        presence.register(userId, session);
        relay.flushPending(userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        UUID userId = userId(session);
        ClientFrame frame;
        try {
            frame = json.readValue(message.getPayload(), ClientFrame.class);
        } catch (JacksonException e) {
            presence.send(userId, new ServerFrame.Error(ErrorCode.VALIDATION_FAILED, "Malformed frame"));
            return;
        }
        switch (frame) {
            case ClientFrame.MessageSend send -> relay.send(userId, send);
            case ClientFrame.MessageAck ack -> relay.ack(userId, ack);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (session.getAttributes().get(EXPIRY_TASK) instanceof ScheduledFuture<?> task) {
            task.cancel(false);
        }
        presence.unregister(userId(session), session);
    }

    @PreDestroy
    void shutdown() {
        expiries.shutdownNow();
    }

    /** The JWT was valid at handshake time; close the socket when it expires so the client refreshes. */
    private void scheduleTokenExpiry(WebSocketSession session) {
        if (!(session.getAttributes().get(WsAuthInterceptor.TOKEN_EXPIRES_AT) instanceof Instant expiresAt)) {
            return;
        }
        long delayMs = Math.max(0, Duration.between(clock.instant(), expiresAt).toMillis());
        session.getAttributes().put(EXPIRY_TASK, expiries.schedule(() -> close(session), delayMs, TimeUnit.MILLISECONDS));
    }

    private static void close(WebSocketSession session) {
        try {
            session.close(TOKEN_EXPIRED);
        } catch (IOException ignored) {
            // already gone
        }
    }

    private static UUID userId(WebSocketSession session) {
        return (UUID) session.getAttributes().get(WsAuthInterceptor.USER_ID);
    }
}
