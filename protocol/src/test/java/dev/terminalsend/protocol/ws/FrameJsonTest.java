package dev.terminalsend.protocol.ws;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FrameJsonTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void clientFrameRoundTripsWithTypeDiscriminator() {
        ClientFrame frame = new ClientFrame.MessageSend(UUID.randomUUID(), UUID.randomUUID(), "fp", "bm9uY2U=",
                "Y3Q=", Instant.parse("2026-10-05T12:00:00Z"));

        String text = json.writeValueAsString(frame);

        assertThat(text).contains("\"type\":\"message.send\"");
        assertThat(json.readValue(text, ClientFrame.class)).isEqualTo(frame);
    }

    @Test
    void parsesAckFrame() {
        UUID id = UUID.randomUUID();
        String text = "{\"type\":\"message.ack\",\"ids\":[\"" + id + "\"]}";

        assertThat(json.readValue(text, ClientFrame.class)).isEqualTo(new ClientFrame.MessageAck(List.of(id)));
    }

    @Test
    void serverFrameRoundTrips() {
        ServerFrame frame = new ServerFrame.MessageDelivered(UUID.randomUUID());

        String text = json.writeValueAsString(frame);

        assertThat(text).contains("\"type\":\"message.delivered\"");
        assertThat(json.readValue(text, ServerFrame.class)).isEqualTo(frame);
    }
}
