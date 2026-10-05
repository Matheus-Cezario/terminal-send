package dev.terminalsend.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentifiersTest {

    @Test
    void acceptsValidHandlesCaseInsensitively() {
        assertThat(Identifiers.isHandle("ts-7KQ2MX")).isTrue();
        assertThat(Identifiers.isHandle(" TS-7kq2mx ")).isTrue();
    }

    @Test
    void rejectsHandlesWithAmbiguousCharacters() {
        assertThat(Identifiers.isHandle("ts-7KQ2MI")).isFalse();
        assertThat(Identifiers.isHandle("ts-7KQ2M")).isFalse();
        assertThat(Identifiers.isHandle("ana@x.com")).isFalse();
    }

    @Test
    void validatesAndNormalizesEmail() {
        assertThat(Identifiers.isEmail("Ana@Exemplo.com")).isTrue();
        assertThat(Identifiers.isEmail("ana@localhost")).isFalse();
        assertThat(Identifiers.isEmail("ana exemplo.com")).isFalse();
        assertThat(Identifiers.normalizeEmail("  Ana@Exemplo.COM ")).isEqualTo("ana@exemplo.com");
    }
}
