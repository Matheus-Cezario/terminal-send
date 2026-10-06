package dev.terminalsend.server.common;

import dev.terminalsend.server.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final FixedWindowLimiter limiter = new FixedWindowLimiter(3, Duration.ofMinutes(1), clock);

    @Test
    void allowsUpToMaxPerKeyPerWindow() {
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("b")).isTrue();
    }

    @Test
    void resetsInTheNextWindow() {
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire("a");
        }
        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("a")).isTrue();
    }
}
