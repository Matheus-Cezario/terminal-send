package dev.terminalsend.server.ws;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

/** Authenticates the upgrade request with the same bearer JWT used by the REST API. */
@Component
class WsAuthInterceptor implements HandshakeInterceptor {

    static final String USER_ID = "userId";
    static final String TOKEN_EXPIRES_AT = "tokenExpiresAt";

    private final JwtDecoder decoder;

    WsAuthInterceptor(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            Jwt jwt = decoder.decode(header.substring("Bearer ".length()));
            attributes.put(USER_ID, UUID.fromString(jwt.getSubject()));
            attributes.put(TOKEN_EXPIRES_AT, jwt.getExpiresAt());
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {
    }
}
