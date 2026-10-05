package dev.terminalsend.server.auth;

import dev.terminalsend.protocol.Identifiers;
import dev.terminalsend.protocol.rest.AuthDtos.LoginRequest;
import dev.terminalsend.protocol.rest.AuthDtos.RegisterRequest;
import dev.terminalsend.protocol.rest.AuthDtos.RegisterResponse;
import dev.terminalsend.protocol.rest.AuthDtos.TokenPair;
import dev.terminalsend.protocol.rest.AuthDtos.VerifyRequest;
import dev.terminalsend.protocol.rest.ErrorCode;
import dev.terminalsend.server.common.ApiException;
import dev.terminalsend.server.user.HandleGenerator;
import dev.terminalsend.server.user.User;
import dev.terminalsend.server.user.UserRepository;
import dev.terminalsend.server.verification.EmailVerificationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    static final int MIN_PASSWORD_LENGTH = 10;
    static final int MAX_PASSWORD_LENGTH = 128;

    private final UserRepository users;
    private final HandleGenerator handles;
    private final PasswordEncoder passwords;
    private final EmailVerificationService verification;
    private final TokenService tokens;
    private final LoginAttemptLimiter limiter;
    private final Clock clock;
    /** Compared against when the email is unknown, so response time doesn't reveal which emails exist. */
    private final String dummyHash;

    public AuthService(UserRepository users, HandleGenerator handles, PasswordEncoder passwords,
                       EmailVerificationService verification, TokenService tokens, LoginAttemptLimiter limiter,
                       Clock clock) {
        this.users = users;
        this.handles = handles;
        this.passwords = passwords;
        this.verification = verification;
        this.tokens = tokens;
        this.limiter = limiter;
        this.clock = clock;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    /**
     * Re-registering an unverified email replaces its password and issues a new code: the account
     * belongs to whoever proves ownership of the inbox, and {@link #verify} requires that same password.
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = requireEmail(request.email());
        String password = requirePassword(request.password());

        Optional<User> existing = users.findByEmail(email);
        if (existing.isPresent()) {
            User user = existing.get();
            if (user.isEmailVerified()) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.EMAIL_ALREADY_REGISTERED,
                        "Email already registered");
            }
            verification.issueRespectingCooldown(user);
            user.changePasswordHash(passwords.encode(password));
            return new RegisterResponse(user.getId(), user.getHandle());
        }

        User user = users.save(new User(UUID.randomUUID(), email, handles.newUniqueHandle(),
                passwords.encode(password), clock.instant()));
        verification.issue(user);
        return new RegisterResponse(user.getId(), user.getHandle());
    }

    /** Doesn't roll back on {@link ApiException} so failed code attempts stay counted. */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenPair verify(VerifyRequest request) {
        User user = findUnverified(request.email())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_VERIFICATION_CODE,
                        "Invalid code"));
        if (request.password() == null || !passwords.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        verification.verify(user, request.code());
        return tokens.issue(user);
    }

    /** Always succeeds silently for unknown or already-verified emails. */
    @Transactional
    public void resendCode(String rawEmail) {
        findUnverified(rawEmail).ifPresent(verification::issueRespectingCooldown);
    }

    @Transactional
    public TokenPair login(LoginRequest request) {
        if (request.email() == null || request.password() == null) {
            throw invalidCredentials();
        }
        String email = Identifiers.normalizeEmail(request.email());
        if (limiter.isLocked(email)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_ATTEMPTS,
                    "Too many failed logins; try again later");
        }

        Optional<User> user = users.findByEmail(email);
        boolean passwordOk = passwords.matches(request.password(), user.map(User::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !passwordOk) {
            limiter.recordFailure(email);
            throw invalidCredentials();
        }
        limiter.reset(email);

        if (!user.get().isEmailVerified()) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCode.EMAIL_NOT_VERIFIED, "Email not verified");
        }
        return tokens.issue(user.get());
    }

    private Optional<User> findUnverified(String rawEmail) {
        if (!Identifiers.isEmail(rawEmail)) {
            return Optional.empty();
        }
        return users.findByEmail(Identifiers.normalizeEmail(rawEmail)).filter(u -> !u.isEmailVerified());
    }

    private static String requireEmail(String raw) {
        if (!Identifiers.isEmail(raw)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Invalid email");
        }
        return Identifiers.normalizeEmail(raw);
    }

    private static String requirePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                    "Password must have between %d and %d characters"
                            .formatted(MIN_PASSWORD_LENGTH, MAX_PASSWORD_LENGTH));
        }
        return password;
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
    }
}
