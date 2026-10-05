package dev.terminalsend.server.key;

import dev.terminalsend.protocol.KeyFingerprints;
import dev.terminalsend.protocol.rest.AuthDtos.TokenPair;
import dev.terminalsend.protocol.rest.ConnectionDtos.InviteRequest;
import dev.terminalsend.protocol.rest.KeyDtos.PublicKeyUpload;
import dev.terminalsend.server.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RecordApplicationEvents
class KeyFlowTest extends IntegrationTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    JdbcClient jdbc;

    private TokenPair ana;
    private TokenPair bruno;

    @BeforeEach
    void users() throws Exception {
        ana = registerAndVerify();
        bruno = registerAndVerify();
    }

    @Test
    void connectedPeersCanReadEachOthersKey() throws Exception {
        byte[] key = randomKey();
        publish(ana, Base64.getEncoder().encodeToString(key)).andExpect(status().isNoContent());
        connect();

        mvc.perform(get("/api/v1/users/{id}/key", ana.user().id()).header("Authorization", bearer(bruno)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").value(Base64.getEncoder().encodeToString(key)))
                .andExpect(jsonPath("$.fingerprint").value(KeyFingerprints.of(key)));
    }

    @Test
    void strangersAndPendingInvitesCannotReadKeys() throws Exception {
        publish(ana, Base64.getEncoder().encodeToString(randomKey()));

        getKey(bruno, ana.user().id()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("KEY_NOT_FOUND"));

        invite(bruno, ana.user().email());
        getKey(bruno, ana.user().id()).andExpect(status().isNotFound());
    }

    @Test
    void ownerCanReadOwnKeyAndMissingKeyIs404() throws Exception {
        getKey(ana, ana.user().id()).andExpect(status().isNotFound());

        publish(ana, Base64.getEncoder().encodeToString(randomKey()));
        getKey(ana, ana.user().id()).andExpect(status().isOk());
    }

    @Test
    void rejectsMalformedKeys() throws Exception {
        publish(ana, Base64.getEncoder().encodeToString(new byte[31])).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        publish(ana, "not base64!").andExpect(status().isBadRequest());
        publish(ana, null).andExpect(status().isBadRequest());
    }

    @Test
    void rotatingKeyDropsQueuedEnvelopesAndNotifies(ApplicationEvents events) throws Exception {
        String first = Base64.getEncoder().encodeToString(randomKey());
        publish(ana, first);
        publish(ana, first);
        assertThat(events.stream(KeyChanged.class)).as("first publish and re-publish are not changes").isEmpty();

        queueEnvelopeFor(ana.user().id(), bruno.user().id());
        byte[] second = randomKey();
        publish(ana, Base64.getEncoder().encodeToString(second)).andExpect(status().isNoContent());

        assertThat(events.stream(KeyChanged.class))
                .containsExactly(new KeyChanged(ana.user().id(), KeyFingerprints.of(second)));
        assertThat(jdbc.sql("select count(*) from pending_messages where recipient_id = :id")
                .param("id", ana.user().id()).query(Long.class).single()).isZero();
    }

    private void connect() throws Exception {
        invite(ana, bruno.user().email());
        invite(bruno, ana.user().email());
    }

    private void invite(TokenPair who, String target) throws Exception {
        mvc.perform(post("/api/v1/connections").header("Authorization", bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new InviteRequest(target))))
                .andExpect(status().isAccepted());
    }

    private ResultActions publish(TokenPair who, String base64Key) throws Exception {
        return mvc.perform(put("/api/v1/me/key").header("Authorization", bearer(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new PublicKeyUpload(base64Key))));
    }

    private ResultActions getKey(TokenPair who, UUID userId) throws Exception {
        return mvc.perform(get("/api/v1/users/{id}/key", userId).header("Authorization", bearer(who)));
    }

    private void queueEnvelopeFor(UUID recipient, UUID sender) {
        Instant now = Instant.now();
        jdbc.sql("""
                        insert into pending_messages
                        (id, sender_id, recipient_id, recipient_key_fp, nonce, ciphertext, sent_at, created_at, expires_at)
                        values (:id, :sender, :recipient, 'fp', :nonce, :ct, :now, :now, :now)
                        """)
                .param("id", UUID.randomUUID()).param("sender", sender).param("recipient", recipient)
                .param("nonce", new byte[12]).param("ct", new byte[16])
                .param("now", java.sql.Timestamp.from(now))
                .update();
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
    }
}
