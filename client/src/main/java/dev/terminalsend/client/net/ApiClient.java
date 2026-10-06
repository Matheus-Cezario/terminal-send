package dev.terminalsend.client.net;

import dev.terminalsend.protocol.rest.AuthDtos.LoginRequest;
import dev.terminalsend.protocol.rest.AuthDtos.RefreshRequest;
import dev.terminalsend.protocol.rest.AuthDtos.RegisterRequest;
import dev.terminalsend.protocol.rest.AuthDtos.RegisterResponse;
import dev.terminalsend.protocol.rest.AuthDtos.ResendCodeRequest;
import dev.terminalsend.protocol.rest.AuthDtos.TokenPair;
import dev.terminalsend.protocol.rest.AuthDtos.UserView;
import dev.terminalsend.protocol.rest.AuthDtos.VerifyRequest;
import dev.terminalsend.protocol.rest.ConnectionDtos.ConnectionView;
import dev.terminalsend.protocol.rest.ConnectionDtos.InviteRequest;
import dev.terminalsend.protocol.rest.ErrorCode;
import dev.terminalsend.protocol.rest.KeyDtos.PublicKeyUpload;
import dev.terminalsend.protocol.rest.KeyDtos.PublicKeyView;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Typed client for the REST API. Refreshes the access token transparently once on a 401. */
public class ApiClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final URI baseUrl;
    private final HttpClient http;
    private final JsonMapper json;
    private volatile TokenPair tokens;

    public ApiClient(URI baseUrl, HttpClient http, JsonMapper json) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.json = json;
    }

    // --- auth (no bearer token) ---

    public RegisterResponse register(String email, String password) {
        return call("POST", "/auth/register", new RegisterRequest(email, password), RegisterResponse.class, false);
    }

    public TokenPair verify(String email, String password, String code) {
        return remember(call("POST", "/auth/verify", new VerifyRequest(email, password, code), TokenPair.class, false));
    }

    public void resendCode(String email) {
        call("POST", "/auth/verify/resend", new ResendCodeRequest(email), Void.class, false);
    }

    public TokenPair login(String email, String password) {
        return remember(call("POST", "/auth/login", new LoginRequest(email, password), TokenPair.class, false));
    }

    /** Rotates the refresh token. Skips the call if another thread already refreshed past {@code staleAccess}. */
    public synchronized TokenPair refresh(String staleAccess) {
        TokenPair current = requireTokens();
        if (staleAccess != null && !staleAccess.equals(current.accessToken())) {
            return current;
        }
        return remember(call("POST", "/auth/refresh", new RefreshRequest(current.refreshToken()), TokenPair.class,
                false));
    }

    public void logout() {
        TokenPair current = tokens;
        tokens = null;
        if (current != null) {
            call("POST", "/auth/logout", new RefreshRequest(current.refreshToken()), Void.class, false);
        }
    }

    public String accessToken() {
        return requireTokens().accessToken();
    }

    // --- authenticated ---

    public UserView me() {
        return call("GET", "/me", null, UserView.class, true);
    }

    public void publishKey(byte[] rawPublicKey) {
        call("PUT", "/me/key", new PublicKeyUpload(Base64.getEncoder().encodeToString(rawPublicKey)), Void.class,
                true);
    }

    public Optional<PublicKeyView> publicKey(UUID userId) {
        try {
            return Optional.of(call("GET", "/users/" + userId + "/key", null, PublicKeyView.class, true));
        } catch (ApiError e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public void invite(String target) {
        call("POST", "/connections", new InviteRequest(target), Void.class, true);
    }

    public List<ConnectionView> connections() {
        return List.of(call("GET", "/connections", null, ConnectionView[].class, true));
    }

    public ConnectionView accept(UUID connectionId) {
        return call("POST", "/connections/" + connectionId + "/accept", null, ConnectionView.class, true);
    }

    public void reject(UUID connectionId) {
        call("POST", "/connections/" + connectionId + "/reject", null, Void.class, true);
    }

    public void remove(UUID connectionId) {
        call("DELETE", "/connections/" + connectionId, null, Void.class, true);
    }

    // --- plumbing ---

    private <T> T call(String method, String path, Object body, Class<T> type, boolean authenticated) {
        String access = authenticated ? accessToken() : null;
        HttpResponse<String> response = send(method, path, body, access);
        if (authenticated && response.statusCode() == 401) {
            access = refresh(access).accessToken();
            response = send(method, path, body, access);
        }
        if (response.statusCode() / 100 != 2) {
            throw toError(response);
        }
        if (type == Void.class || response.body().isEmpty()) {
            return null;
        }
        return json.readValue(response.body(), type);
    }

    private HttpResponse<String> send(String method, String path, Object body, String accessToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1" + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json");
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiError(0, null, "Servidor indisponível: " + baseUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiError(0, null, "Interrompido");
        }
    }

    private ApiError toError(HttpResponse<String> response) {
        ErrorCode code = null;
        String message = "HTTP " + response.statusCode();
        try {
            JsonNode problem = json.readTree(response.body());
            if (problem.hasNonNull("code")) {
                code = ErrorCode.valueOf(problem.get("code").asString());
            }
            if (problem.hasNonNull("detail")) {
                message = problem.get("detail").asString();
            }
        } catch (JacksonException | IllegalArgumentException ignored) {
            // not a problem+json body; keep the generic message
        }
        return new ApiError(response.statusCode(), code, message);
    }

    private TokenPair remember(TokenPair pair) {
        tokens = pair;
        return pair;
    }

    private TokenPair requireTokens() {
        TokenPair current = tokens;
        if (current == null) {
            throw new ApiError(401, ErrorCode.INVALID_REFRESH_TOKEN, "Não autenticado");
        }
        return current;
    }
}
