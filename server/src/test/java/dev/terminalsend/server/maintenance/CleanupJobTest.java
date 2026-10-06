package dev.terminalsend.server.maintenance;

import dev.terminalsend.protocol.rest.AuthDtos.LoginRequest;
import dev.terminalsend.protocol.rest.AuthDtos.RefreshRequest;
import dev.terminalsend.protocol.rest.AuthDtos.TokenPair;
import dev.terminalsend.server.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CleanupJobTest extends IntegrationTest {

    @Autowired
    CleanupJob job;

    @Autowired
    JdbcClient jdbc;

    @Test
    void removesStaleUnverifiedAccountsButKeepsVerifiedAndRecentOnes() throws Exception {
        String stale = register();
        TokenPair verified = registerAndVerify();

        clock.advance(Duration.ofDays(8));
        String recent = register();
        job.run();

        assertThat(userExists(stale)).isFalse();
        assertThat(userExists(verified.user().email())).isTrue();
        assertThat(userExists(recent)).isTrue();
        // The stale email is free to register again.
        postJson("/api/v1/auth/register", new dev.terminalsend.protocol.rest.AuthDtos.RegisterRequest(stale, PASSWORD))
                .andExpect(status().isCreated());
    }

    @Test
    void removesExpiredRefreshTokensOnly() throws Exception {
        TokenPair tokens = registerAndVerify();
        postJson("/api/v1/auth/login", new LoginRequest(tokens.user().email(), PASSWORD)).andExpect(status().isOk());

        clock.advance(Duration.ofDays(31));
        TokenPair fresh = read(postJson("/api/v1/auth/login", new LoginRequest(tokens.user().email(), PASSWORD))
                .andExpect(status().isOk()), TokenPair.class);
        job.run();

        assertThat(tokenCount(tokens.user().id().toString())).isEqualTo(1);
        postJson("/api/v1/auth/refresh", new RefreshRequest(fresh.refreshToken())).andExpect(status().isOk());
    }

    private boolean userExists(String email) {
        return jdbc.sql("select count(*) from users where email = :email").param("email", email)
                .query(Long.class).single() > 0;
    }

    private long tokenCount(String userId) {
        return jdbc.sql("select count(*) from refresh_tokens where user_id = cast(:id as uuid)").param("id", userId)
                .query(Long.class).single();
    }
}
