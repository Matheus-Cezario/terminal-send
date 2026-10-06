package dev.terminalsend.client.net;

import dev.terminalsend.protocol.ws.ClientFrame;
import dev.terminalsend.protocol.ws.ServerFrame;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Keeps one WebSocket to {@code /ws} alive: reconnects with exponential backoff and jitter, refreshes the access
 * token when the server closes with 4401 and stops for good when another session replaces this one (4001).
 */
public class RealtimeClient implements AutoCloseable {

    public interface Listener {
        void onConnected();

        void onFrame(ServerFrame frame);

        void onDisconnected();

        /** Another login for the same account took over; no more reconnects. */
        void onReplaced();
    }

    static final Duration MIN_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final Duration PING_INTERVAL = Duration.ofSeconds(30);

    private final URI url;
    private final HttpClient http;
    private final JsonMapper json;
    private final ApiClient api;
    private final Listener listener;
    private volatile boolean running;
    private volatile WebSocket socket;
    private Thread loop;

    public RealtimeClient(URI url, HttpClient http, JsonMapper json, ApiClient api, Listener listener) {
        this.url = url;
        this.http = http;
        this.json = json;
        this.api = api;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        loop = Thread.ofVirtual().name("realtime").start(this::run);
    }

    public boolean isConnected() {
        WebSocket current = socket;
        return current != null && !current.isOutputClosed();
    }

    /** Returns false when offline; callers keep the frame (outbox) and retry on reconnect. */
    public boolean send(ClientFrame frame) {
        WebSocket current = socket;
        if (current == null || current.isOutputClosed()) {
            return false;
        }
        String text = json.writeValueAsString(frame);
        synchronized (this) {
            try {
                current.sendText(text, true).get(10, TimeUnit.SECONDS);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        WebSocket current = socket;
        if (current != null) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "bye").exceptionally(e -> null);
        }
        if (loop != null) {
            loop.interrupt();
        }
    }

    private void run() {
        Duration backoff = MIN_BACKOFF;
        while (running) {
            CompletableFuture<Integer> closed = new CompletableFuture<>();
            try {
                socket = connect(closed);
            } catch (RuntimeException e) {
                // Handshake failures are usually an expired token; refreshing is cheap and harmless.
                tryRefresh();
                sleep(backoff);
                backoff = next(backoff);
                continue;
            }
            backoff = MIN_BACKOFF;
            listener.onConnected();
            int code = awaitClose(closed);
            socket = null;
            if (!running) {
                return;
            }
            listener.onDisconnected();
            if (code == ServerFrame.CLOSE_SESSION_REPLACED) {
                running = false;
                listener.onReplaced();
                return;
            }
            if (code == ServerFrame.CLOSE_TOKEN_EXPIRED) {
                tryRefresh();
                continue;
            }
            sleep(backoff);
            backoff = next(backoff);
        }
    }

    private WebSocket connect(CompletableFuture<Integer> closed) {
        return http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + api.accessToken())
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(url, new FrameListener(closed))
                .join();
    }

    /** Waits for the socket to close, pinging periodically so idle proxies don't drop it. */
    private int awaitClose(CompletableFuture<Integer> closed) {
        while (running) {
            try {
                return closed.get(PING_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                WebSocket current = socket;
                if (current != null) {
                    current.sendPing(ByteBuffer.allocate(0));
                }
            } catch (InterruptedException e) {
                return WebSocket.NORMAL_CLOSURE;
            } catch (java.util.concurrent.ExecutionException e) {
                return -1;
            }
        }
        return WebSocket.NORMAL_CLOSURE;
    }

    private void tryRefresh() {
        try {
            api.refresh(api.accessToken());
        } catch (ApiError ignored) {
            // offline or session revoked; the next attempt will tell
        }
    }

    private void sleep(Duration duration) {
        long jitter = ThreadLocalRandom.current().nextLong(duration.toMillis() / 4 + 1);
        try {
            Thread.sleep(duration.toMillis() + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    static Duration next(Duration backoff) {
        Duration doubled = backoff.multipliedBy(2);
        return doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
    }

    private final class FrameListener implements WebSocket.Listener {

        private final CompletableFuture<Integer> closed;
        private final StringBuilder partial = new StringBuilder();

        FrameListener(CompletableFuture<Integer> closed) {
            this.closed = closed;
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                try {
                    listener.onFrame(json.readValue(text, ServerFrame.class));
                } catch (JacksonException ignored) {
                    // unknown frame type from a newer server; skip it
                } catch (RuntimeException e) {
                    // a listener bug must not kill the socket
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.complete(-1);
        }
    }
}
