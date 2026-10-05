package dev.terminalsend.client.crypto;

import org.junit.jupiter.api.Test;

import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class X25519Test {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void matchesRfc7748DiffieHellmanVector() throws Exception {
        // RFC 7748 §6.1
        XECPrivateKey alice = X25519.decodePrivate(
                HEX.parseHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"));
        XECPublicKey bob = X25519.decodePublic(
                HEX.parseHex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"));

        assertThat(HEX.formatHex(X25519.agree(alice, bob)))
                .isEqualTo("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742");
    }

    @Test
    void rawEncodingRoundTrips() {
        KeyPair pair = X25519.generate();
        byte[] rawPublic = X25519.encodePublic((XECPublicKey) pair.getPublic());
        byte[] rawPrivate = X25519.encodePrivate((XECPrivateKey) pair.getPrivate());

        assertThat(rawPublic).hasSize(32);
        assertThat(X25519.encodePublic(X25519.decodePublic(rawPublic))).isEqualTo(rawPublic);
        assertThat(X25519.encodePrivate(X25519.decodePrivate(rawPrivate))).isEqualTo(rawPrivate);
    }

    @Test
    void rejectsSmallOrderPeerKeys() {
        XECPrivateKey mine = (XECPrivateKey) X25519.generate().getPrivate();

        assertThatThrownBy(() -> X25519.agree(mine, X25519.decodePublic(new byte[32])))
                .isInstanceOf(InvalidKeyException.class);
    }

    @Test
    void rejectsWrongLength() {
        assertThatThrownBy(() -> X25519.decodePublic(new byte[31])).isInstanceOf(IllegalArgumentException.class);
    }
}
