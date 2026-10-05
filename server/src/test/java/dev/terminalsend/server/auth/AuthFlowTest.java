package dev.terminalsend.server.auth;

import dev.terminalsend.protocol.Identifiers;
import dev.terminalsend.protocol.rest.AuthDtos.LoginRequest;
import dev.terminalsend.protocol.rest.AuthDtos.RefreshRequest;
import dev.terminalsend.protocol.rest.AuthDtos.RegisterRequest;
import dev.terminalsend.protocol.rest.AuthDtos.RegisterResponse;
import dev.terminalsend.protocol.rest.AuthDtos.ResendCodeRequest;
import dev.terminalsend.protocol.rest.AuthDtos.TokenPair;
import dev.terminalsend.protocol.rest.AuthDtos.VerifyRequest;
import dev.terminalsend.server.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Test
    void registerVerifyLoginAndFetchMe() throws Exception {
        String email = uniqueEmail();

        RegisterResponse registered = read(
                postJson("/api/v1/auth/register", new RegisterRequest(email.toUpperCase(), PASSWORD))
                        .andExpect(status().isCreated()),
                RegisterResponse.class);
        assertThat(Identifiers.isHandle(registered.handle())).isTrue();

        postJson("/api/v1/auth/login", new LoginRequest(email, PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        String code = latestCodeFor(email);
        postJson("/api/v1/auth/verify", new VerifyRequest(email, PASSWORD, wrong(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
        postJson("/api/v1/auth/verify", new VerifyRequest(email, "not the password", code))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        TokenPair tokens = read(postJson("/api/v1/auth/verify", new VerifyRequest(email, PASSWORD, code))
                .andExpect(status().isOk()), TokenPair.class);
        assertThat(tokens.user().emailVerified()).isTrue();

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.handle").value(registered.handle()))
                .andExpect(jsonPath("$.emailVerified").value(true));

        postJson("/api/v1/auth/login", new LoginRequest(email, PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void codeExpiresAfterTtl() throws Exception {
        String email = register();

        clock.advance(Duration.ofMinutes(16));

        postJson("/api/v1/auth/verify", new VerifyRequest(email, PASSWORD, latestCodeFor(email)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_CODE_EXPIRED"));
    }

    @Test
    void codeIsLockedAfterMaxAttemptsEvenIfCorrect() throws Exception {
        String email = register();
        String code = latestCodeFor(email);

        for (int i = 0; i < 5; i++) {
            postJson("/api/v1/auth/verify", new VerifyRequest(email, PASSWORD, wrong(code)))
                    .andExpect(status().isBadRequest());
        }

        postJson("/api/v1/auth/verify", new VerifyRequest(email, PASSWORD, code))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
    }

    @Test
    void reRegisteringUnverifiedEmailReplacesPasswordAfterCooldown() throws Exception {
        String email = register();

        postJson("/api/v1/auth/register", new RegisterRequest(email, "another password"))
                .andExpect(status().isTooManyRequests());

        clock.advance(Duration.ofSeconds(61));
        postJson("/api/v1/auth/register", new RegisterRequest(email, "another password"))
                .andExpect(status().isCreated());
        String code = latestCodeFor(email);

        postJson("/api/v1/auth/verify", new VerifyRequest(email, PASSWORD, code))
                .andExpect(status().isUnauthorized());
        postJson("/api/v1/auth/verify", new VerifyRequest(email, "another password", code))
                .andExpect(status().isOk());
    }

    @Test
    void registeringVerifiedEmailConflicts() throws Exception {
        String email = registerAndVerify().user().email();

        postJson("/api/v1/auth/register", new RegisterRequest(email, PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void resendHonoursCooldownAndIsSilentForUnknownEmails() throws Exception {
        String email = register();

        postJson("/api/v1/auth/verify/resend", new ResendCodeRequest(email)).andExpect(status().isTooManyRequests());
        clock.advance(Duration.ofSeconds(61));
        postJson("/api/v1/auth/verify/resend", new ResendCodeRequest(email)).andExpect(status().isAccepted());
        assertThat(emailsSentTo(email)).isEqualTo(2);

        postJson("/api/v1/auth/verify/resend", new ResendCodeRequest(uniqueEmail())).andExpect(status().isAccepted());
    }

    @Test
    void refreshRotatesAndReuseRevokesTheWholeFamily() throws Exception {
        TokenPair first = registerAndVerify();

        TokenPair second = read(postJson("/api/v1/auth/refresh", new RefreshRequest(first.refreshToken()))
                .andExpect(status().isOk()), TokenPair.class);
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());

        // Replaying the rotated token signals a leak: both it and its successor stop working.
        postJson("/api/v1/auth/refresh", new RefreshRequest(first.refreshToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        postJson("/api/v1/auth/refresh", new RefreshRequest(second.refreshToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        TokenPair tokens = registerAndVerify();

        postJson("/api/v1/auth/logout", new RefreshRequest(tokens.refreshToken())).andExpect(status().isNoContent());

        postJson("/api/v1/auth/refresh", new RefreshRequest(tokens.refreshToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginIsThrottledAfterRepeatedFailures() throws Exception {
        String email = registerAndVerify().user().email();

        for (int i = 0; i < 5; i++) {
            postJson("/api/v1/auth/login", new LoginRequest(email, "wrong password"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        postJson("/api/v1/auth/login", new LoginRequest(email, PASSWORD))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void unknownEmailAndWrongPasswordLookTheSame() throws Exception {
        postJson("/api/v1/auth/login", new LoginRequest(uniqueEmail(), PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void rejectsShortPasswordsAndInvalidEmails() throws Exception {
        postJson("/api/v1/auth/register", new RegisterRequest(uniqueEmail(), "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postJson("/api/v1/auth/register", new RegisterRequest("not-an-email", PASSWORD))
                .andExpect(status().isBadRequest());
    }

    private String register() throws Exception {
        String email = uniqueEmail();
        postJson("/api/v1/auth/register", new RegisterRequest(email, PASSWORD)).andExpect(status().isCreated());
        return email;
    }

    private TokenPair registerAndVerify() throws Exception {
        String email = register();
        return read(postJson("/api/v1/auth/verify", new VerifyRequest(email, PASSWORD, latestCodeFor(email)))
                .andExpect(status().isOk()), TokenPair.class);
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }
}
