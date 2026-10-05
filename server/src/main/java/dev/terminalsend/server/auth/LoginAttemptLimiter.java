package dev.terminalsend.server.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locks an email for {@link #WINDOW} after {@link #MAX_FAILURES} failed logins inside that window.
 * In-memory: fine for the single-instance v1, moves to Redis together with presence (tech-spec §9).
 */
@Component
public class LoginAttemptLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean isLocked(String email) {
        Deque<Instant> attempts = failures.get(email);
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            prune(attempts);
            return attempts.size() >= MAX_FAILURES;
        }
    }

    public void recordFailure(String email) {
        Deque<Instant> attempts = failures.computeIfAbsent(email, e -> new ArrayDeque<>());
        synchronized (attempts) {
            prune(attempts);
            attempts.addLast(clock.instant());
        }
    }

    public void reset(String email) {
        failures.remove(email);
    }

    private void prune(Deque<Instant> attempts) {
        Instant cutoff = clock.instant().minus(WINDOW);
        while (!attempts.isEmpty() && !attempts.peekFirst().isAfter(cutoff)) {
            attempts.removeFirst();
        }
    }
}
