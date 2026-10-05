package dev.terminalsend.server.verification;

import dev.terminalsend.server.config.TerminalSendProperties;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationCodesTest {

    private final VerificationCodes codes = new VerificationCodes(new TerminalSendProperties(
            new TerminalSendProperties.Jwt("test-secret-test-secret-test-secret!", null, null), null, null, null));

    @Test
    void codesAreSixDigits() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(codes.newCode()).matches("\\d{6}");
        }
    }

    @Test
    void hashIsBoundToUser() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        String hash = codes.hash(alice, "123456");

        assertThat(codes.matches(alice, "123456", hash)).isTrue();
        assertThat(codes.matches(alice, "123457", hash)).isFalse();
        assertThat(codes.matches(bob, "123456", hash)).isFalse();
    }
}
