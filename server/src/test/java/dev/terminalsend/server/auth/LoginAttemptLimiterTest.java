package dev.terminalsend.server.auth;

import dev.terminalsend.server.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

    @Test
    void locksAfterMaxFailuresAndUnlocksAfterWindow() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES - 1; i++) {
            limiter.recordFailure("a@x.com");
        }
        assertThat(limiter.isLocked("a@x.com")).isFalse();

        limiter.recordFailure("a@x.com");
        assertThat(limiter.isLocked("a@x.com")).isTrue();
        assertThat(limiter.isLocked("b@x.com")).isFalse();

        clock.advance(LoginAttemptLimiter.WINDOW.plus(Duration.ofSeconds(1)));
        assertThat(limiter.isLocked("a@x.com")).isFalse();
    }

    @Test
    void resetClearsFailures() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
            limiter.recordFailure("a@x.com");
        }
        limiter.reset("a@x.com");

        assertThat(limiter.isLocked("a@x.com")).isFalse();
    }
}
