package dev.terminalsend.server.verification;

import dev.terminalsend.protocol.rest.ErrorCode;
import dev.terminalsend.server.common.ApiException;
import dev.terminalsend.server.config.TerminalSendProperties;
import dev.terminalsend.server.user.User;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class EmailVerificationService {

    private final EmailVerificationRepository verifications;
    private final VerificationCodes codes;
    private final ApplicationEventPublisher events;
    private final TerminalSendProperties.Verification config;
    private final Clock clock;

    public EmailVerificationService(EmailVerificationRepository verifications, VerificationCodes codes,
                                    ApplicationEventPublisher events, TerminalSendProperties props, Clock clock) {
        this.verifications = verifications;
        this.codes = codes;
        this.events = events;
        this.config = props.verification();
        this.clock = clock;
    }

    /** Issues a fresh code (older ones stop being checked) and emails it after commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void issue(User user) {
        Instant now = clock.instant();
        String code = codes.newCode();
        verifications.save(new EmailVerification(UUID.randomUUID(), user.getId(), codes.hash(user.getId(), code),
                now.plus(config.codeTtl()), now));
        events.publishEvent(new VerificationCodeIssued(user.getEmail(), code));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void issueRespectingCooldown(User user) {
        Instant now = clock.instant();
        boolean coolingDown = verifications.findFirstByUserIdOrderByCreatedAtDesc(user.getId())
                .map(v -> now.isBefore(v.getCreatedAt().plus(config.resendCooldown())))
                .orElse(false);
        if (coolingDown) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED,
                    "Wait before requesting another code");
        }
        issue(user);
    }

    /**
     * Checks {@code code} against the latest issued code and marks the user verified.
     * Neither this method nor the caller's transaction may roll back on {@link ApiException}, otherwise the
     * failed-attempt counter is lost (a participating method would mark the shared transaction rollback-only).
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = ApiException.class)
    public void verify(User user, String code) {
        Instant now = clock.instant();
        EmailVerification latest = verifications.findFirstByUserIdOrderByCreatedAtDesc(user.getId())
                .filter(v -> v.isUsable(now))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VERIFICATION_CODE_EXPIRED,
                        "Code expired; request a new one"));
        if (latest.getAttempts() >= config.maxAttempts()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_ATTEMPTS,
                    "Too many attempts; request a new code");
        }
        if (code == null || !codes.matches(user.getId(), code.strip(), latest.getCodeHash())) {
            latest.recordFailedAttempt();
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_VERIFICATION_CODE, "Invalid code");
        }
        latest.consume(now);
        user.markEmailVerified(now);
    }
}
