package dev.terminalsend.server.key;

import dev.terminalsend.protocol.rest.KeyDtos.PublicKeyUpload;
import dev.terminalsend.protocol.rest.KeyDtos.PublicKeyView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class KeyController {

    private final KeyService keys;

    public KeyController(KeyService keys) {
        this.keys = keys;
    }

    @PutMapping("/me/key")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void publish(@AuthenticationPrincipal Jwt jwt, @RequestBody PublicKeyUpload body) {
        keys.publish(UUID.fromString(jwt.getSubject()), body.publicKey());
    }

    @GetMapping("/users/{userId}/key")
    PublicKeyView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId) {
        return keys.get(UUID.fromString(jwt.getSubject()), userId);
    }
}
