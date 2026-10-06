package dev.terminalsend.client.app;

import dev.terminalsend.client.ClientConfig;
import dev.terminalsend.client.crypto.DecryptionException;
import dev.terminalsend.client.crypto.IdentityKeyFile;
import dev.terminalsend.client.crypto.X25519;
import dev.terminalsend.client.net.ApiClient;
import dev.terminalsend.client.net.RealtimeClient;
import dev.terminalsend.client.store.LocalStore;
import dev.terminalsend.protocol.KeyFingerprints;
import dev.terminalsend.protocol.rest.AuthDtos.RegisterResponse;
import dev.terminalsend.protocol.rest.AuthDtos.TokenPair;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.time.Clock;
import java.time.Duration;

/**
 * Login, registration and email verification. A successful login unlocks (or creates) this device's identity
 * key with the same password and publishes its public half, then opens the account's local history.
 */
public final class AuthService {

    private final ClientConfig config;
    private final HttpClient http;
    private final JsonMapper json;
    private final ApiClient api;
    private final IdentityKeyFile keyFiles;
    private final Clock clock;

    public AuthService(ClientConfig config) {
        this(config, new IdentityKeyFile(), Clock.systemUTC());
    }

    AuthService(ClientConfig config, IdentityKeyFile keyFiles, Clock clock) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.json = JsonMapper.builder().build();
        this.api = new ApiClient(config.serverUrl(), http, json);
        this.keyFiles = keyFiles;
        this.clock = clock;
    }

    public RegisterResponse register(String email, String password) {
        return api.register(email, password);
    }

    public void resendCode(String email) {
        api.resendCode(email);
    }

    public Session verify(String email, String password, String code, ChatEvents events) throws IOException {
        return open(api.verify(email, password, code), password, events);
    }

    /** @throws dev.terminalsend.client.net.ApiError with {@code EMAIL_NOT_VERIFIED} when a code is still needed */
    public Session login(String email, String password, ChatEvents events) throws IOException {
        return open(api.login(email, password), password, events);
    }

    private Session open(TokenPair tokens, String password, ChatEvents events) throws IOException {
        Path accountDir = config.accountDir(tokens.user().handle());
        KeyPair identity = loadOrCreateIdentity(accountDir.resolve("identity.key"), password);
        byte[] rawPublic = X25519.encodePublic((XECPublicKey) identity.getPublic());
        api.publishKey(rawPublic);

        LocalStore store = LocalStore.open(accountDir.resolve("history.db"));
        ContactService contacts = new ContactService(api, store, events);
        ChatService chat = new ChatService(tokens.user().id(), (XECPrivateKey) identity.getPrivate(), store, contacts,
                events, json, clock);
        Session session = new Session(tokens.user(), KeyFingerprints.of(rawPublic), api, store, contacts, chat,
                events);
        session.attach(new RealtimeClient(config.webSocketUrl(), http, json, api, session.realtimeListener()));
        return session;
    }

    /** First login on this device creates the key; the server then drops envelopes sealed for any older device. */
    private KeyPair loadOrCreateIdentity(Path file, String password) throws IOException {
        char[] secret = password.toCharArray();
        if (Files.exists(file)) {
            try {
                return keyFiles.load(file, secret);
            } catch (DecryptionException e) {
                throw new UserFacingException("Não foi possível abrir a chave local com essa senha: " + file);
            }
        }
        KeyPair identity = X25519.generate();
        keyFiles.save(file, identity, secret);
        return identity;
    }
}
