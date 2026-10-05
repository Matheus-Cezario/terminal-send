package dev.terminalsend.server.auth;

import dev.terminalsend.protocol.rest.AuthDtos.LoginRequest;
import dev.terminalsend.protocol.rest.AuthDtos.RefreshRequest;
import dev.terminalsend.protocol.rest.AuthDtos.RegisterRequest;
import dev.terminalsend.protocol.rest.AuthDtos.RegisterResponse;
import dev.terminalsend.protocol.rest.AuthDtos.ResendCodeRequest;
import dev.terminalsend.protocol.rest.AuthDtos.TokenPair;
import dev.terminalsend.protocol.rest.AuthDtos.VerifyRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final TokenService tokens;

    public AuthController(AuthService auth, TokenService tokens) {
        this.auth = auth;
        this.tokens = tokens;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    RegisterResponse register(@RequestBody RegisterRequest request) {
        return auth.register(request);
    }

    @PostMapping("/verify")
    TokenPair verify(@RequestBody VerifyRequest request) {
        return auth.verify(request);
    }

    @PostMapping("/verify/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void resend(@RequestBody ResendCodeRequest request) {
        auth.resendCode(request.email());
    }

    @PostMapping("/login")
    TokenPair login(@RequestBody LoginRequest request) {
        return auth.login(request);
    }

    @PostMapping("/refresh")
    TokenPair refresh(@RequestBody RefreshRequest request) {
        return tokens.rotate(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestBody RefreshRequest request) {
        tokens.revokeFamily(request.refreshToken());
    }
}
