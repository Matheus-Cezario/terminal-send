package dev.terminalsend.server.auth;

import dev.terminalsend.protocol.rest.AuthDtos.TokenPair;
import dev.terminalsend.server.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JwtValidationTest extends IntegrationTest {

    @Autowired
    JwtEncoder encoder;

    @Test
    void acceptsOwnTokens() throws Exception {
        TokenPair tokens = registerAndVerify();

        mvc.perform(get("/api/v1/me").header("Authorization", bearer(tokens))).andExpect(status().isOk());
    }

    @Test
    void rejectsCorrectlySignedTokensFromAnotherIssuer() throws Exception {
        TokenPair tokens = registerAndVerify();

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token("someone-else", tokens)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredTokens() throws Exception {
        TokenPair tokens = registerAndVerify();
        Instant past = Instant.now().minus(Duration.ofHours(1));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(JwtService.ISSUER).subject(tokens.user().id().toString())
                .issuedAt(past.minusSeconds(60)).expiresAt(past).build();
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    private String token(String issuer, TokenPair tokens) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject(tokens.user().id().toString())
                .issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(5))).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
