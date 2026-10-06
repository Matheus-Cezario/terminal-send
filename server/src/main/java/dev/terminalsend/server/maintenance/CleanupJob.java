package dev.terminalsend.server.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Hourly housekeeping of data the server has no reason to keep (tech-spec RF1.7). */
@Component
public class CleanupJob {

    static final Duration UNVERIFIED_ACCOUNT_TTL = Duration.ofDays(7);
    static final Duration EXPIRED_CODE_GRACE = Duration.ofDays(1);

    private static final Logger log = LoggerFactory.getLogger(CleanupJob.class);

    private final JdbcClient jdbc;
    private final Clock clock;

    public CleanupJob(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Result(int unverifiedUsers, int verificationCodes, int refreshTokens) {
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public Result run() {
        Instant now = clock.instant();
        // Cascades to the account's codes and tokens. Unverified users can't have connections or messages.
        int users = jdbc.sql("delete from users where email_verified_at is null and created_at < :cutoff")
                .param("cutoff", Timestamp.from(now.minus(UNVERIFIED_ACCOUNT_TTL))).update();
        int codes = jdbc.sql("delete from email_verifications where expires_at < :cutoff")
                .param("cutoff", Timestamp.from(now.minus(EXPIRED_CODE_GRACE))).update();
        // Revoked-but-unexpired tokens stay: they are what detects reuse of a stolen refresh token.
        int tokens = jdbc.sql("delete from refresh_tokens where expires_at < :now")
                .param("now", Timestamp.from(now)).update();
        Result result = new Result(users, codes, tokens);
        if (users + codes + tokens > 0) {
            log.info("Cleanup removed {}", result);
        }
        return result;
    }
}
