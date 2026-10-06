package dev.terminalsend.server.messaging;

import dev.terminalsend.protocol.ws.ServerFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is online, and the only place frames are written. One session per user (tech-spec §6).
 * Swap for a Redis-backed implementation to run several instances (tech-spec §9).
 */
@Component
public class PresenceRegistry {

    public static final CloseStatus SESSION_REPLACED =
            new CloseStatus(ServerFrame.CLOSE_SESSION_REPLACED, "Session replaced by a newer connection");

    private static final Logger log = LoggerFactory.getLogger(PresenceRegistry.class);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_LIMIT_BYTES = 1024 * 1024;

    private final Map<UUID, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final JsonMapper json;

    public PresenceRegistry(JsonMapper json) {
        this.json = json;
    }

    public void register(UUID userId, WebSocketSession session) {
        // WebSocketSession is not thread-safe for sends; the decorator serializes them.
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES);
        WebSocketSession previous = sessions.put(userId, safe);
        if (previous != null && !previous.getId().equals(session.getId())) {
            close(previous, SESSION_REPLACED);
        }
    }

    public void unregister(UUID userId, WebSocketSession session) {
        sessions.computeIfPresent(userId, (id, current) -> current.getId().equals(session.getId()) ? null : current);
    }

    public boolean isOnline(UUID userId) {
        return sessions.containsKey(userId);
    }

    /** Best effort: returns false if the user is offline or the write failed. */
    public boolean send(UUID userId, ServerFrame frame) {
        WebSocketSession session = sessions.get(userId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            session.sendMessage(new TextMessage(json.writeValueAsString(frame)));
            return true;
        } catch (IOException | RuntimeException e) {
            log.debug("Failed to send {} to {}", frame.getClass().getSimpleName(), userId, e);
            return false;
        }
    }

    private static void close(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Failed to close replaced session", e);
        }
    }
}
