package dev.terminalsend.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyFingerprintsTest {

    @Test
    void fingerprintIsHexSha256OfRawKey() {
        // SHA-256 of 32 zero bytes.
        assertThat(KeyFingerprints.of(new byte[32]))
                .isEqualTo("66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925");
    }

    @Test
    void displayGroupsFirstHalf() {
        String fp = KeyFingerprints.of(new byte[32]);

        assertThat(KeyFingerprints.display(fp)).isEqualTo("6668 7aad f862 bd77 6c8f c18b 8e9f 8e20");
    }

    @Test
    void rejectsWrongLength() {
        assertThatThrownBy(() -> KeyFingerprints.of(new byte[33])).isInstanceOf(IllegalArgumentException.class);
    }
}
