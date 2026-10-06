package dev.terminalsend.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientConfigTest {

    @TempDir
    Path home;

    @Test
    void defaultsToLocalServerWhenNoConfigFile() throws Exception {
        ClientConfig config = ClientConfig.load(home, null);

        assertThat(config.serverUrl()).isEqualTo(ClientConfig.DEFAULT_SERVER);
        assertThat(config.webSocketUrl()).isEqualTo(URI.create("ws://localhost:8080/ws"));
    }

    @Test
    void readsServerFromFileAndDerivesSecureWebSocket() throws Exception {
        Files.writeString(home.resolve("config.properties"), "serverUrl=https://chat.example.com/\n");

        ClientConfig config = ClientConfig.load(home, null);

        assertThat(config.serverUrl()).isEqualTo(URI.create("https://chat.example.com"));
        assertThat(config.webSocketUrl()).isEqualTo(URI.create("wss://chat.example.com/ws"));
    }

    @Test
    void cliOverrideWinsOverFile() throws Exception {
        Files.writeString(home.resolve("config.properties"), "serverUrl=https://chat.example.com\n");

        assertThat(ClientConfig.load(home, "https://10.0.0.5:9000").serverUrl())
                .isEqualTo(URI.create("https://10.0.0.5:9000"));
    }

    @Test
    void plainHttpOnlyForLoopbackUnlessInsecure() throws Exception {
        assertThat(ClientConfig.load(home, "http://127.0.0.1:8080").serverUrl().getHost()).isEqualTo("127.0.0.1");
        assertThatThrownBy(() -> ClientConfig.load(home, "http://chat.example.com"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https://");
        assertThat(ClientConfig.load(home, "http://chat.example.com", true).serverUrl())
                .isEqualTo(URI.create("http://chat.example.com"));
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertThatThrownBy(() -> ClientConfig.load(home, "ftp://x")).isInstanceOf(IllegalArgumentException.class);
    }
}
