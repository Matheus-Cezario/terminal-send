package dev.terminalsend.client.net;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeClientTest {

    @Test
    void backoffDoublesUpToTheCap() {
        Duration backoff = RealtimeClient.MIN_BACKOFF;
        backoff = RealtimeClient.next(backoff);
        assertThat(backoff).isEqualTo(Duration.ofSeconds(2));

        for (int i = 0; i < 10; i++) {
            backoff = RealtimeClient.next(backoff);
        }
        assertThat(backoff).isEqualTo(RealtimeClient.MAX_BACKOFF);
    }
}
