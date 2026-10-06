package dev.terminalsend.server.messaging;

import dev.terminalsend.server.common.FixedWindowLimiter;
import dev.terminalsend.server.config.TerminalSendProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/** Per-sender messages-per-second cap for the relay. */
@Component
public class SendRateLimiter {

    private final FixedWindowLimiter limiter;

    public SendRateLimiter(TerminalSendProperties props, Clock clock) {
        this.limiter = new FixedWindowLimiter(props.messaging().maxMessagesPerSecond(), Duration.ofSeconds(1), clock);
    }

    public boolean tryAcquire(UUID sender) {
        return limiter.tryAcquire(sender.toString());
    }
}
