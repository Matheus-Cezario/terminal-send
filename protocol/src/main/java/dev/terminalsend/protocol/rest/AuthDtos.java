package dev.terminalsend.protocol.rest;

import java.time.Instant;
import java.util.UUID;

/** Request/response bodies for {@code /api/v1/auth/*}. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(String email, String password) {
    }

    public record RegisterResponse(UUID userId, String handle) {
    }

    public record VerifyRequest(String email, String code) {
    }

    public record ResendCodeRequest(String email) {
    }

    public record LoginRequest(String email, String password) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    public record TokenPair(String accessToken, Instant accessTokenExpiresAt, String refreshToken, UserView user) {
    }

    public record UserView(UUID id, String email, String handle, boolean emailVerified) {
    }
}
