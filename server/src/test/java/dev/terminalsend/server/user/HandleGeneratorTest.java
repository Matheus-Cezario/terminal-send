package dev.terminalsend.server.user;

import dev.terminalsend.protocol.Identifiers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HandleGeneratorTest {

    private final HandleGenerator generator = new HandleGenerator(null);

    @Test
    void generatesHandlesAcceptedByProtocolValidation() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(Identifiers.isHandle(generator.randomHandle())).isTrue();
        }
    }

    @Test
    void alphabetMatchesProtocolPattern() {
        assertThat(HandleGenerator.ALPHABET).hasSize(32).doesNotContain("I", "L", "O", "U");
    }
}
