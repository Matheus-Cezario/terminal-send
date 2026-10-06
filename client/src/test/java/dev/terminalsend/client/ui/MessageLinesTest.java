package dev.terminalsend.client.ui;

import dev.terminalsend.client.store.StoredMessage;
import dev.terminalsend.client.store.StoredMessage.State;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MessageLinesTest {

    private static final Instant AT = Instant.parse("2026-10-05T12:01:00Z");

    @Test
    void formatsIncomingAndOutgoingWithDeliveryMarks() {
        StoredMessage in = new StoredMessage(UUID.randomUUID(), UUID.randomUUID(), StoredMessage.Direction.IN,
                "oi!", AT, AT, State.RECEIVED);
        StoredMessage out = new StoredMessage(UUID.randomUUID(), UUID.randomUUID(), StoredMessage.Direction.OUT,
                "e aí", AT, null, State.DELIVERED);

        assertThat(MessageLines.format(in, "ana", ZoneOffset.UTC)).isEqualTo("[12:01] ana: oi!");
        assertThat(MessageLines.format(out, "ana", ZoneOffset.UTC)).isEqualTo("[12:01] você: e aí  ✓✓");
        assertThat(MessageLines.mark(State.QUEUED)).isEqualTo("…");
        assertThat(MessageLines.mark(State.FAILED)).isEqualTo("✗");
    }

    @Test
    void wrapsLongLinesOnWordsWithIndent() {
        assertThat(MessageLines.wrap("[12:01] ana: uma mensagem bem comprida que não cabe", 24))
                .containsExactly("[12:01] ana: uma", "    mensagem bem", "    comprida que não", "    cabe");
    }

    @Test
    void breaksWordsLongerThanTheWidth() {
        assertThat(MessageLines.wrap("x".repeat(25), 20)).containsExactly("x".repeat(20), "    " + "x".repeat(5));
    }
}
