package dev.terminalsend.server.support;

import dev.terminalsend.protocol.ws.ClientFrame;
import dev.terminalsend.protocol.ws.ServerFrame;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Headless WebSocket client for tests, built on the JDK client the real app will use. */
public final class TestSocket implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final BlockingQueue<ServerFrame> frames = new LinkedBlockingQueue<>();
    private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
    private final JsonMapper json;
    private final WebSocket socket;

    private TestSocket(JsonMapper json, URI uri, String accessToken) {
        this.json = json;
        this.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer " + accessToken)
                .buildAsync(uri, new Listener())
                .join();
    }

    public static TestSocket connect(JsonMapper json, int port, String accessToken) {
        return new TestSocket(json, URI.create("ws://localhost:" + port + "/ws"), accessToken);
    }

    public void send(ClientFrame frame) {
        socket.sendText(json.writeValueAsString(frame), true).join();
    }

    public void sendRaw(String text) {
        socket.sendText(text, true).join();
    }

    public <T extends ServerFrame> T next(Class<T> type) throws InterruptedException {
        ServerFrame frame = frames.poll(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(frame).as("expected a %s frame", type.getSimpleName()).isInstanceOf(type);
        return type.cast(frame);
    }

    public void expectNothing() throws InterruptedException {
        assertThat(frames.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    public int awaitClose() {
        return closeCode.orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
    }

    @Override
    public void close() {
        if (!socket.isOutputClosed()) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").exceptionally(e -> null).join();
        }
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                frames.add(json.readValue(partial.toString(), ServerFrame.class));
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeCode.completeExceptionally(error);
        }
    }
}
