package dev.terminalsend.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalSendPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(TerminalSendProperties.class)
    static class PropsOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropsOnly.class, ProductionSafetyCheck.class)
            .withPropertyValues(
                    "terminal-send.jwt.access-token-ttl=15m", "terminal-send.jwt.refresh-token-ttl=30d",
                    "terminal-send.verification.code-ttl=15m", "terminal-send.verification.max-attempts=5",
                    "terminal-send.verification.resend-cooldown=60s",
                    "terminal-send.messaging.pending-ttl=30d", "terminal-send.messaging.max-ciphertext-bytes=65536",
                    "terminal-send.messaging.max-messages-per-second=20",
                    "terminal-send.mail.from=no-reply@x.com",
                    "terminal-send.rate-limits.auth-per-ip.max=1", "terminal-send.rate-limits.auth-per-ip.window=1m",
                    "terminal-send.rate-limits.emails-per-ip.max=1", "terminal-send.rate-limits.emails-per-ip.window=1m",
                    "terminal-send.rate-limits.invites-per-user.max=1",
                    "terminal-send.rate-limits.invites-per-user.window=1m");

    @Test
    void startsWithAProperSecret() {
        runner.withPropertyValues("terminal-send.jwt.secret=a-long-random-secret-of-at-least-32-bytes")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void refusesAnUnresolvedPlaceholderSecret() {
        // What Boot binds when TS_JWT_SECRET is missing from the environment: the literal placeholder text.
        runner.withPropertyValues("terminal-send.jwt.secret=${TS_JWT_SECRET_THAT_IS_NOT_SET_ANYWHERE_AT_ALL}")
                .run(ctx -> assertThat(ctx).getFailure().rootCause()
                        .hasMessageContaining("unresolved placeholder"));
    }

    @Test
    void refusesShortSecrets() {
        runner.withPropertyValues("terminal-send.jwt.secret=short")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void refusesTheDevSecretOnlyInProd() {
        String dev = "terminal-send.jwt.secret=" + TerminalSendProperties.DEV_SECRET_PREFIX + "0123456789abcdef0123456789";

        runner.withPropertyValues(dev).run(ctx -> assertThat(ctx).hasNotFailed());
        runner.withPropertyValues(dev, "spring.profiles.active=prod").run(ctx -> assertThat(ctx).getFailure()
                .rootCause().hasMessageContaining("TS_JWT_SECRET must be set"));
    }
}
