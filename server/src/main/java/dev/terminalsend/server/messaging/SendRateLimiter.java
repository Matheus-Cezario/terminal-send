package dev.terminalsend.server.messaging;

import dev.terminalsend.server.config.TerminalSendProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed one-second window per sender. In-memory, like presence, for the single-instance v1. */
@Component
public class SendRateLimiter {

    private record Window(long second, int count) {
    }

    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private final int maxPerSecond;
    private final Clock clock;

    public SendRateLimiter(TerminalSendProperties props, Clock clock) {
        this.maxPerSecond = props.messaging().maxMessagesPerSecond();
        this.clock = clock;
    }

    public boolean tryAcquire(UUID sender) {
        long second = clock.millis() / 1000;
        Window window = windows.merge(sender, new Window(second, 1),
                (old, fresh) -> old.second() == second ? new Window(second, old.count() + 1) : fresh);
        return window.count() <= maxPerSecond;
    }

    public void forget(UUID sender) {
        windows.remove(sender);
    }
}
