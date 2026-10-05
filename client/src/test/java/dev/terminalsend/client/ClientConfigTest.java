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

        assertThat(ClientConfig.load(home, "http://10.0.0.5:9000").serverUrl())
                .isEqualTo(URI.create("http://10.0.0.5:9000"));
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertThatThrownBy(() -> ClientConfig.load(home, "ftp://x")).isInstanceOf(IllegalArgumentException.class);
    }
}
