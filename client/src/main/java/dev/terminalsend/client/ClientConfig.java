package dev.terminalsend.client;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client settings, read from {@code ~/.terminal-send/config.properties} and overridable by CLI flags.
 *
 * @param home      base directory holding config and per-account data ({@code <handle>/history.db}, keys, session)
 * @param serverUrl HTTP(S) base URL of the server; the WebSocket URL is derived from it
 */
public record ClientConfig(Path home, URI serverUrl) {

    public static final URI DEFAULT_SERVER = URI.create("http://localhost:8080");

    public static ClientConfig load(Path home, String serverOverride) throws IOException {
        Properties props = new Properties();
        Path file = home.resolve("config.properties");
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                props.load(reader);
            }
        }
        String server = serverOverride != null ? serverOverride : props.getProperty("serverUrl");
        URI serverUrl = server == null ? DEFAULT_SERVER : URI.create(stripTrailingSlash(server));
        if (!"http".equals(serverUrl.getScheme()) && !"https".equals(serverUrl.getScheme())) {
            throw new IllegalArgumentException("serverUrl must be http(s): " + server);
        }
        return new ClientConfig(home, serverUrl);
    }

    public static Path defaultHome() {
        return Path.of(System.getProperty("user.home"), ".terminal-send");
    }

    public URI webSocketUrl() {
        String scheme = "https".equals(serverUrl.getScheme()) ? "wss" : "ws";
        return URI.create(scheme + serverUrl.toString().substring(serverUrl.getScheme().length()) + "/ws");
    }

    public Path accountDir(String handle) {
        return home.resolve(handle);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
