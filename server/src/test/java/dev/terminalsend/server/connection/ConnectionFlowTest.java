package dev.terminalsend.server.connection;

import dev.terminalsend.protocol.rest.AuthDtos.TokenPair;
import dev.terminalsend.protocol.rest.ConnectionDtos.ConnectionView;
import dev.terminalsend.protocol.rest.ConnectionDtos.Direction;
import dev.terminalsend.protocol.rest.ConnectionDtos.InviteRequest;
import dev.terminalsend.protocol.rest.ConnectionDtos.Status;
import dev.terminalsend.server.connection.ConnectionEvents.ConnectionAccepted;
import dev.terminalsend.server.connection.ConnectionEvents.ConnectionRemoved;
import dev.terminalsend.server.connection.ConnectionEvents.ConnectionRequested;
import dev.terminalsend.server.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RecordApplicationEvents
class ConnectionFlowTest extends IntegrationTest {

    private TokenPair ana;
    private TokenPair bruno;

    @BeforeEach
    void users() throws Exception {
        ana = registerAndVerify();
        bruno = registerAndVerify();
    }

    @Test
    void inviteByEmailThenAccept(ApplicationEvents events) throws Exception {
        invite(ana, bruno.user().email().toUpperCase()).andExpect(status().isAccepted());

        ConnectionView outgoing = single(list(ana, null));
        assertThat(outgoing.direction()).isEqualTo(Direction.OUTGOING);
        assertThat(outgoing.status()).isEqualTo(Status.PENDING);
        assertThat(outgoing.peer().handle()).isEqualTo(bruno.user().handle());

        ConnectionView incoming = single(list(bruno, Status.PENDING));
        assertThat(incoming.direction()).isEqualTo(Direction.INCOMING);
        assertThat(events.stream(ConnectionRequested.class))
                .containsExactly(new ConnectionRequested(incoming.id(), bruno.user().id()));

        mvc.perform(post("/api/v1/connections/{id}/accept", incoming.id()).header("Authorization", bearer(bruno)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.peer.email").value(ana.user().email()));

        assertThat(single(list(ana, Status.ACCEPTED)).peer().id()).isEqualTo(bruno.user().id());
        assertThat(list(ana, Status.PENDING)).isEmpty();
        assertThat(events.stream(ConnectionAccepted.class))
                .containsExactly(new ConnectionAccepted(incoming.id(), ana.user().id()));
    }

    @Test
    void inviteByHandleIsCaseInsensitive() throws Exception {
        invite(ana, bruno.user().handle().toLowerCase()).andExpect(status().isAccepted());

        assertThat(list(bruno, Status.PENDING)).hasSize(1);
    }

    @Test
    void unknownSelfAndUnverifiedTargetsLookLikeSuccess(ApplicationEvents events) throws Exception {
        String unverified = register();

        invite(ana, uniqueEmail()).andExpect(status().isAccepted());
        invite(ana, "ts-ZZZZZZ").andExpect(status().isAccepted());
        invite(ana, ana.user().email()).andExpect(status().isAccepted());
        invite(ana, unverified).andExpect(status().isAccepted());

        assertThat(list(ana, null)).isEmpty();
        assertThat(events.stream(ConnectionRequested.class)).isEmpty();
    }

    @Test
    void malformedTargetIsAValidationError() throws Exception {
        invite(ana, "bruno").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectionIsSilentForTheRequester() throws Exception {
        invite(ana, bruno.user().email());
        UUID id = single(list(bruno, null)).id();

        mvc.perform(post("/api/v1/connections/{id}/reject", id).header("Authorization", bearer(bruno)))
                .andExpect(status().isNoContent());

        assertThat(single(list(ana, null)).status()).isEqualTo(Status.PENDING);
        assertThat(list(bruno, null)).isEmpty();
        // Inviting again does not resurface the invite for the person who rejected it.
        invite(ana, bruno.user().email());
        assertThat(list(bruno, null)).isEmpty();
        mvc.perform(post("/api/v1/connections/{id}/accept", id).header("Authorization", bearer(bruno)))
                .andExpect(status().isNotFound());
    }

    @Test
    void whoeverRejectedCanInviteBack() throws Exception {
        invite(ana, bruno.user().email());
        UUID id = single(list(bruno, null)).id();
        mvc.perform(post("/api/v1/connections/{id}/reject", id).header("Authorization", bearer(bruno)));

        invite(bruno, ana.user().email()).andExpect(status().isAccepted());

        ConnectionView forAna = single(list(ana, null));
        assertThat(forAna.direction()).isEqualTo(Direction.INCOMING);
        assertThat(forAna.status()).isEqualTo(Status.PENDING);
    }

    @Test
    void mutualInvitesConnectImmediately(ApplicationEvents events) throws Exception {
        invite(ana, bruno.user().email());
        invite(bruno, ana.user().email());

        assertThat(single(list(ana, null)).status()).isEqualTo(Status.ACCEPTED);
        assertThat(single(list(bruno, null)).status()).isEqualTo(Status.ACCEPTED);
        assertThat(events.stream(ConnectionAccepted.class)).hasSize(1);
    }

    @Test
    void onlyTheAddresseeCanAcceptAndOutsidersSeeNothing() throws Exception {
        TokenPair carla = registerAndVerify();
        invite(ana, bruno.user().email());
        UUID id = single(list(ana, null)).id();

        mvc.perform(post("/api/v1/connections/{id}/accept", id).header("Authorization", bearer(ana)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/connections/{id}/accept", id).header("Authorization", bearer(carla)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/connections/{id}", id).header("Authorization", bearer(carla)))
                .andExpect(status().isNotFound());
    }

    @Test
    void removingAContactNotifiesThePeerAndAllowsReconnecting(ApplicationEvents events) throws Exception {
        invite(ana, bruno.user().email());
        invite(bruno, ana.user().email());
        UUID id = single(list(ana, null)).id();

        mvc.perform(delete("/api/v1/connections/{id}", id).header("Authorization", bearer(bruno)))
                .andExpect(status().isNoContent());

        assertThat(list(ana, null)).isEmpty();
        assertThat(events.stream(ConnectionRemoved.class))
                .containsExactly(new ConnectionRemoved(id, ana.user().id()));

        invite(ana, bruno.user().email());
        assertThat(list(bruno, Status.PENDING)).hasSize(1);
    }

    @Test
    void cancellingARejectedInviteDoesNotNotifyWhoRejected(ApplicationEvents events) throws Exception {
        invite(ana, bruno.user().email());
        UUID id = single(list(bruno, null)).id();
        mvc.perform(post("/api/v1/connections/{id}/reject", id).header("Authorization", bearer(bruno)));

        mvc.perform(delete("/api/v1/connections/{id}", id).header("Authorization", bearer(ana)))
                .andExpect(status().isNoContent());

        assertThat(events.stream(ConnectionRemoved.class)).isEmpty();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/connections")).andExpect(status().isUnauthorized());
    }

    private ResultActions invite(TokenPair who, String target) throws Exception {
        return mvc.perform(post("/api/v1/connections").header("Authorization", bearer(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new InviteRequest(target))));
    }

    private List<ConnectionView> list(TokenPair who, Status status) throws Exception {
        var request = get("/api/v1/connections").header("Authorization", bearer(who));
        if (status != null) {
            request.param("status", status.name());
        }
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return List.of(json.readValue(body, ConnectionView[].class));
    }

    private static ConnectionView single(List<ConnectionView> views) {
        assertThat(views).hasSize(1);
        return views.getFirst();
    }
}
