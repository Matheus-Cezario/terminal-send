package dev.terminalsend.server.common;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allows {@code max} events per key in consecutive fixed windows. In-memory, which matches the single-instance
 * v1; it moves to Redis together with presence when the server scales out (tech-spec §9).
 */
public final class FixedWindowLimiter {

    private static final int PRUNE_THRESHOLD = 10_000;

    private record Window(long index, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int max;
    private final long windowMillis;
    private final Clock clock;

    public FixedWindowLimiter(int max, Duration window, Clock clock) {
        this.max = max;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    public boolean tryAcquire(String key) {
        long index = clock.millis() / windowMillis;
        if (windows.size() > PRUNE_THRESHOLD) {
            windows.values().removeIf(w -> w.index() < index);
        }
        Window window = windows.merge(key, new Window(index, 1),
                (old, fresh) -> old.index() == index ? new Window(index, old.count() + 1) : fresh);
        return window.count() <= max;
    }
}
