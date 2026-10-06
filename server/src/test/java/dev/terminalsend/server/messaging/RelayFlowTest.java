package dev.terminalsend.server.messaging;

import dev.terminalsend.client.crypto.EnvelopeCipher;
import dev.terminalsend.client.crypto.EnvelopeCipher.Sealed;
import dev.terminalsend.client.crypto.PairKeys;
import dev.terminalsend.client.crypto.X25519;
import dev.terminalsend.protocol.KeyFingerprints;
import dev.terminalsend.protocol.rest.AuthDtos.TokenPair;
import dev.terminalsend.protocol.rest.ConnectionDtos.InviteRequest;
import dev.terminalsend.protocol.rest.ErrorCode;
import dev.terminalsend.protocol.rest.KeyDtos.PublicKeyUpload;
import dev.terminalsend.protocol.ws.ClientFrame.MessageAck;
import dev.terminalsend.protocol.ws.ClientFrame.MessageSend;
import dev.terminalsend.protocol.ws.ServerFrame;
import dev.terminalsend.protocol.ws.ServerFrame.MessageAccepted;
import dev.terminalsend.protocol.ws.ServerFrame.MessageDeliver;
import dev.terminalsend.protocol.ws.ServerFrame.MessageDelivered;
import dev.terminalsend.protocol.ws.ServerFrame.MessageRejected;
import dev.terminalsend.server.support.IntegrationTest;
import dev.terminalsend.server.support.TestSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.crypto.SecretKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RelayFlowTest extends IntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private Party ana;
    private Party bruno;

    /** A user with a device key, as the real client will have after login. */
    private record Party(TokenPair tokens, KeyPair keys) {
        UUID id() {
            return tokens.user().id();
        }

        String fingerprint() {
            return KeyFingerprints.of(X25519.encodePublic((XECPublicKey) keys.getPublic()));
        }
    }

    @BeforeEach
    void connectedUsersWithKeys() throws Exception {
        ana = newParty();
        bruno = newParty();
        connect(ana, bruno);
    }

    @Test
    void deliversEncryptedMessageLiveAndReportsDelivery() throws Exception {
        try (TestSocket anaWs = open(ana); TestSocket brunoWs = open(bruno)) {
            MessageSend send = encrypt(ana, bruno, "oi bruno! 🚀");
            anaWs.send(send);

            assertThat(anaWs.next(MessageAccepted.class).id()).isEqualTo(send.id());
            MessageDeliver deliver = brunoWs.next(MessageDeliver.class);
            assertThat(deliver.from()).isEqualTo(ana.id());
            assertThat(deliver.senderKeyFp()).isEqualTo(ana.fingerprint());
            assertThat(decrypt(bruno, ana, deliver)).isEqualTo("oi bruno! 🚀");

            brunoWs.send(new MessageAck(List.of(deliver.id())));
            assertThat(anaWs.next(MessageDelivered.class).id()).isEqualTo(send.id());
            assertThat(pendingCount()).isZero();
        }
    }

    @Test
    void storesForOfflineRecipientAndRedeliversUntilAcked() throws Exception {
        MessageSend first = encrypt(ana, bruno, "primeira");
        MessageSend second = encrypt(ana, bruno, "segunda");
        try (TestSocket anaWs = open(ana)) {
            anaWs.send(first);
            anaWs.next(MessageAccepted.class);
            anaWs.send(second);
            anaWs.next(MessageAccepted.class);
        }

        try (TestSocket brunoWs = open(bruno)) {
            assertThat(decrypt(bruno, ana, brunoWs.next(MessageDeliver.class))).isEqualTo("primeira");
            assertThat(decrypt(bruno, ana, brunoWs.next(MessageDeliver.class))).isEqualTo("segunda");
            brunoWs.send(new MessageAck(List.of(first.id())));
        }

        // Unacknowledged messages come back on the next connection (at-least-once).
        try (TestSocket brunoWs = open(bruno)) {
            assertThat(brunoWs.next(MessageDeliver.class).id()).isEqualTo(second.id());
            brunoWs.expectNothing();
        }
    }

    @Test
    void retryingTheSameMessageIdIsIdempotent() throws Exception {
        MessageSend send = encrypt(ana, bruno, "retry");
        try (TestSocket anaWs = open(ana)) {
            anaWs.send(send);
            anaWs.next(MessageAccepted.class);
            anaWs.send(send);
            assertThat(anaWs.next(MessageAccepted.class).id()).isEqualTo(send.id());
        }
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    void rejectsMessagesToStrangers() throws Exception {
        Party carla = newParty();
        try (TestSocket anaWs = open(ana)) {
            MessageSend send = encrypt(ana, carla, "oi?");
            anaWs.send(send);

            assertThat(anaWs.next(MessageRejected.class))
                    .isEqualTo(new MessageRejected(send.id(), ErrorCode.NOT_CONNECTED));
        }
        assertThat(pendingCount()).isZero();
    }

    @Test
    void rejectsMessagesEncryptedForAStaleKey() throws Exception {
        MessageSend send = encrypt(ana, bruno, "chave velha");
        rotateKey(bruno);

        try (TestSocket anaWs = open(ana)) {
            anaWs.send(send);
            assertThat(anaWs.next(MessageRejected.class).code()).isEqualTo(ErrorCode.KEY_MISMATCH);
        }
    }

    @Test
    void rejectsOversizedCiphertext() throws Exception {
        MessageSend huge = new MessageSend(UUID.randomUUID(), bruno.id(), bruno.fingerprint(),
                Base64.getEncoder().encodeToString(new byte[12]),
                Base64.getEncoder().encodeToString(new byte[64 * 1024 + 1]), Instant.now());
        try (TestSocket anaWs = open(ana)) {
            anaWs.send(huge);
            assertThat(anaWs.next(MessageRejected.class).code()).isEqualTo(ErrorCode.TOO_LARGE);
        }
    }

    @Test
    void rateLimitsBursts() throws Exception {
        try (TestSocket anaWs = open(ana)) {
            for (int i = 0; i < 21; i++) {
                anaWs.send(encrypt(ana, bruno, "spam " + i));
            }
            for (int i = 0; i < 20; i++) {
                anaWs.next(MessageAccepted.class);
            }
            assertThat(anaWs.next(MessageRejected.class).code()).isEqualTo(ErrorCode.RATE_LIMITED);
        }
    }

    @Test
    void onlyTheRecipientCanAcknowledge() throws Exception {
        MessageSend send = encrypt(ana, bruno, "só pro bruno");
        try (TestSocket anaWs = open(ana)) {
            anaWs.send(send);
            anaWs.next(MessageAccepted.class);
            anaWs.send(new MessageAck(List.of(send.id())));
            anaWs.expectNothing();
        }
        assertThat(pendingCount()).isEqualTo(1);
    }

    @Test
    void malformedFramesGetAnErrorFrame() throws Exception {
        try (TestSocket anaWs = open(ana)) {
            anaWs.sendRaw("{\"type\":\"nope\"}");
            assertThat(anaWs.next(ServerFrame.Error.class).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
    }

    @Test
    void handshakeRequiresAValidToken() {
        var builder = HttpClient.newHttpClient().newWebSocketBuilder();
        URI uri = URI.create("ws://localhost:" + port + "/ws");

        assertThatThrownBy(() -> builder.buildAsync(uri, new WebSocket.Listener() { }).join())
                .isInstanceOf(CompletionException.class);
        assertThatThrownBy(() -> HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer not-a-jwt")
                .buildAsync(uri, new WebSocket.Listener() { }).join())
                .isInstanceOf(CompletionException.class);
    }

    @Test
    void newConnectionReplacesTheOldOne() throws Exception {
        TestSocket first = open(ana);
        try (TestSocket second = open(ana)) {
            assertThat(first.awaitClose()).isEqualTo(ServerFrame.CLOSE_SESSION_REPLACED);

            MessageSend send = encrypt(bruno, ana, "pro segundo");
            try (TestSocket brunoWs = open(bruno)) {
                brunoWs.send(send);
                brunoWs.next(MessageAccepted.class);
            }
            assertThat(second.next(MessageDeliver.class).id()).isEqualTo(send.id());
        }
    }

    @Test
    void pushesConnectionAndKeyEvents() throws Exception {
        Party carla = newParty();
        try (TestSocket carlaWs = open(carla); TestSocket anaWs = open(ana)) {
            invite(ana, carla.tokens().user().email());
            ServerFrame.ConnectionRequested requested = carlaWs.next(ServerFrame.ConnectionRequested.class);
            assertThat(requested.connection().peer().id()).isEqualTo(ana.id());

            invite(carla, ana.tokens().user().email());
            assertThat(anaWs.next(ServerFrame.ConnectionAccepted.class).connection().peer().id())
                    .isEqualTo(carla.id());

            String newFp = rotateKey(ana);
            assertThat(carlaWs.next(ServerFrame.KeyChanged.class))
                    .isEqualTo(new ServerFrame.KeyChanged(ana.id(), newFp));
        }
    }

    private Party newParty() throws Exception {
        Party party = new Party(registerAndVerify(), X25519.generate());
        publishKey(party.tokens(), party.keys());
        return party;
    }

    private String rotateKey(Party party) throws Exception {
        KeyPair fresh = X25519.generate();
        publishKey(party.tokens(), fresh);
        return KeyFingerprints.of(X25519.encodePublic((XECPublicKey) fresh.getPublic()));
    }

    private void publishKey(TokenPair tokens, KeyPair keys) throws Exception {
        String raw = Base64.getEncoder().encodeToString(X25519.encodePublic((XECPublicKey) keys.getPublic()));
        mvc.perform(put("/api/v1/me/key").header("Authorization", bearer(tokens))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new PublicKeyUpload(raw))))
                .andExpect(status().isNoContent());
    }

    private void connect(Party a, Party b) throws Exception {
        invite(a, b.tokens().user().email());
        invite(b, a.tokens().user().email());
    }

    private void invite(Party who, String target) throws Exception {
        mvc.perform(post("/api/v1/connections").header("Authorization", bearer(who.tokens()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new InviteRequest(target))))
                .andExpect(status().isAccepted());
    }

    private TestSocket open(Party party) {
        return TestSocket.connect(json, port, party.tokens().accessToken());
    }

    private static MessageSend encrypt(Party from, Party to, String text) throws Exception {
        UUID id = UUID.randomUUID();
        Sealed sealed = EnvelopeCipher.seal(pairKey(from, to), id, from.id(), to.id(),
                text.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder b64 = Base64.getEncoder();
        return new MessageSend(id, to.id(), to.fingerprint(), b64.encodeToString(sealed.nonce()),
                b64.encodeToString(sealed.ciphertext()), Instant.now());
    }

    private static String decrypt(Party me, Party from, MessageDeliver deliver) throws Exception {
        Base64.Decoder b64 = Base64.getDecoder();
        byte[] plain = EnvelopeCipher.open(pairKey(me, from), deliver.id(), deliver.from(), me.id(),
                new Sealed(b64.decode(deliver.nonce()), b64.decode(deliver.ciphertext())));
        return new String(plain, StandardCharsets.UTF_8);
    }

    private static SecretKey pairKey(Party me, Party peer) throws Exception {
        return PairKeys.derive((XECPrivateKey) me.keys().getPrivate(), me.id(),
                (XECPublicKey) peer.keys().getPublic(), peer.id());
    }

    private long pendingCount() {
        return jdbc.sql("select count(*) from pending_messages where sender_id in (:a, :b)")
                .param("a", ana.id()).param("b", bruno.id()).query(Long.class).single();
    }
}
