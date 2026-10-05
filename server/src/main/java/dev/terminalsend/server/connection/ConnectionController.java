package dev.terminalsend.server.connection;

import dev.terminalsend.protocol.rest.ConnectionDtos.ConnectionView;
import dev.terminalsend.protocol.rest.ConnectionDtos.InviteRequest;
import dev.terminalsend.protocol.rest.ConnectionDtos.Status;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionController {

    private final ConnectionService connections;

    public ConnectionController(ConnectionService connections) {
        this.connections = connections;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    void invite(@AuthenticationPrincipal Jwt jwt, @RequestBody InviteRequest request) {
        connections.invite(me(jwt), request.target());
    }

    @GetMapping
    List<ConnectionView> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Status status) {
        return connections.list(me(jwt), status);
    }

    @PostMapping("/{id}/accept")
    ConnectionView accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return connections.accept(me(jwt), id);
    }

    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        connections.reject(me(jwt), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        connections.remove(me(jwt), id);
    }

    private static UUID me(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
