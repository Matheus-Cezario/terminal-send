package dev.terminalsend.server.auth;

import dev.terminalsend.protocol.rest.AuthDtos.TokenPair;
import dev.terminalsend.protocol.rest.ErrorCode;
import dev.terminalsend.server.common.ApiException;
import dev.terminalsend.server.config.TerminalSendProperties;
import dev.terminalsend.server.user.User;
import dev.terminalsend.server.user.UserRepository;
import dev.terminalsend.server.user.UserViews;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class TokenService {

    private final SecureRandom random = new SecureRandom();
    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final JwtService jwt;
    private final Duration refreshTtl;
    private final Clock clock;

    public TokenService(RefreshTokenRepository refreshTokens, UserRepository users, JwtService jwt,
                        TerminalSendProperties props, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.jwt = jwt;
        this.refreshTtl = props.jwt().refreshTokenTtl();
        this.clock = clock;
    }

    /** Starts a new token family (one per login). */
    @Transactional(propagation = Propagation.MANDATORY)
    public TokenPair issue(User user) {
        return issue(user, UUID.randomUUID());
    }

    /**
     * Swaps a refresh token for a new pair. Presenting an already-rotated token means it leaked,
     * so the whole family is revoked and the caller must log in again.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenPair rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken current = find(rawToken);
        if (current.isRevoked()) {
            refreshTokens.revokeFamily(current.getFamilyId(), now);
            throw invalid();
        }
        if (current.isExpired(now)) {
            throw invalid();
        }
        current.revoke(now);
        User user = users.findById(current.getUserId()).orElseThrow(TokenService::invalid);
        return issue(user, current.getFamilyId());
    }

    @Transactional
    public void revokeFamily(String rawToken) {
        if (rawToken == null) {
            return;
        }
        refreshTokens.findByTokenHash(hash(rawToken))
                .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), clock.instant()));
    }

    private TokenPair issue(User user, UUID familyId) {
        Instant now = clock.instant();
        String raw = newRawToken();
        refreshTokens.save(new RefreshToken(UUID.randomUUID(), user.getId(), hash(raw), familyId,
                now.plus(refreshTtl), now));
        JwtService.AccessToken access = jwt.issue(user);
        return new TokenPair(access.value(), access.expiresAt(), raw, UserViews.of(user));
    }

    private RefreshToken find(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalid();
        }
        return refreshTokens.findByTokenHash(hash(rawToken)).orElseThrow(TokenService::invalid);
    }

    private String newRawToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Unsalted SHA-256 is fine here: tokens carry 256 bits of entropy. */
    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_REFRESH_TOKEN, "Invalid refresh token");
    }
}
