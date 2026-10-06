package dev.terminalsend.server.auth;

import dev.terminalsend.server.config.TerminalSendProperties;
import dev.terminalsend.server.user.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class JwtService {

    public static final String ISSUER = "terminal-send";

    private final JwtEncoder encoder;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, TerminalSendProperties props, Clock clock) {
        this.encoder = encoder;
        this.ttl = props.jwt().accessTokenTtl();
        this.clock = clock;
    }

    public record AccessToken(String value, Instant expiresAt) {
    }

    public AccessToken issue(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.getId().toString())
                .claim("handle", user.getHandle())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return new AccessToken(encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue(), expiresAt);
    }
}
