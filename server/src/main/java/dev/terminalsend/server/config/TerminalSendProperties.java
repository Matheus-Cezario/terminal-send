package dev.terminalsend.server.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "terminal-send")
public record TerminalSendProperties(Jwt jwt, Verification verification, Messaging messaging, Mail mail) {

    /** {@code secret} must be at least 32 bytes for HS256. */
    public record Jwt(@NotBlank @Size(min = 32) String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {
    }

    public record Verification(Duration codeTtl, int maxAttempts, Duration resendCooldown) {
    }

    public record Messaging(Duration pendingTtl, int maxCiphertextBytes, int maxMessagesPerSecond) {
    }

    public record Mail(@NotBlank String from) {
    }
}
