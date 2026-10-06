package dev.terminalsend.server.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "terminal-send")
public record TerminalSendProperties(@Valid @NotNull Jwt jwt, @Valid @NotNull Verification verification,
                                     @Valid @NotNull Messaging messaging, @Valid @NotNull Mail mail,
                                     @Valid @NotNull RateLimits rateLimits) {

    /** Marker for the development default in application.yml; refused when the prod profile is active. */
    public static final String DEV_SECRET_PREFIX = "dev-only-";

    /**
     * {@code secret} must be at least 32 bytes for HS256. An unset env var reaches us as the literal
     * {@code ${TS_JWT_SECRET}} (Boot keeps unresolvable placeholders), which would be a public, forgeable key.
     */
    public record Jwt(@NotBlank @Size(min = 32) @Pattern(regexp = "^(?!.*\\$\\{).*$",
                              message = "must be set (unresolved placeholder)") String secret,
                      @NotNull Duration accessTokenTtl, @NotNull Duration refreshTokenTtl) {
    }

    public record Verification(Duration codeTtl, int maxAttempts, Duration resendCooldown) {
    }

    public record Messaging(Duration pendingTtl, int maxCiphertextBytes, int maxMessagesPerSecond) {
    }

    public record Mail(@NotBlank @Pattern(regexp = "^(?!.*\\$\\{).*$",
            message = "must be set (unresolved placeholder)") String from) {
    }

    public record Limit(int max, Duration window) {
    }

    /** Abuse limits; see tech-spec §4. */
    public record RateLimits(Limit authPerIp, Limit emailsPerIp, Limit invitesPerUser) {
    }
}
