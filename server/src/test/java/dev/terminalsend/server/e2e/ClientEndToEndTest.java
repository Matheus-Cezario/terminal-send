package dev.terminalsend.server.e2e;

import dev.terminalsend.client.ClientConfig;
import dev.terminalsend.client.app.AuthService;
import dev.terminalsend.client.app.ChatEvents;
import dev.terminalsend.client.app.Commands;
import dev.terminalsend.client.app.Session;
import dev.terminalsend.client.app.UserFacingException;
import dev.terminalsend.client.store.Contact;
import dev.terminalsend.client.store.StoredMessage;
import dev.terminalsend.client.store.StoredMessage.State;
import dev.terminalsend.server.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Two real client sessions (REST + WebSocket + SQLite + E2E crypto) talking through the real server. */
class ClientEndToEndTest extends IntegrationTest {

    @LocalServerPort
    int port;

    @TempDir
    Path anaHome;

    @TempDir
    Path brunoHome;

    @TempDir
    Path brunoSecondDevice;

    private final List<Session> open = new ArrayList<>();
    private final List<String> anaNotices = new CopyOnWriteArrayList<>();

    @AfterEach
    void closeSessions() {
        open.forEach(Session::close);
    }

    @Test
    void twoUsersConnectAndChatIncludingOfflineDeliveryAndKeyChange() throws Exception {
        String anaEmail = uniqueEmail();
        String brunoEmail = uniqueEmail();
        Session ana = signUp(anaHome, anaEmail, recordingNotices());
        Session bruno = signUp(brunoHome, brunoEmail, ChatEvents.NONE);

        // Invite and accept through the same commands the TUI uses.
        assertThat(new Commands(ana).execute("/add " + brunoEmail, null).output().getFirst())
                .startsWith("Convite enviado");
        await("bruno sees the invite", () -> !bruno.contacts().incomingInvites().isEmpty());
        Commands.Result accepted = new Commands(bruno).execute("/accept 1", null);
        UUID anaId = accepted.select();
        UUID brunoId = bruno.me().id();
        await("ana sees bruno accepted with a key", () -> contact(ana, brunoId).isAccepted()
                && contact(ana, brunoId).fingerprint() != null);

        // Live message, end to end encrypted, acknowledged back to the sender.
        StoredMessage hello = ana.chat().send(brunoId, "olá, bruno! 🔐");
        await("bruno stores the message", () -> has(bruno, anaId, "olá, bruno! 🔐"));
        await("ana sees ✓✓", () -> state(ana, brunoId, hello.id()) == State.DELIVERED);

        // Bruno goes offline; the server holds the envelope until he logs in again on the same device.
        bruno.close();
        open.remove(bruno);
        StoredMessage whileAway = ana.chat().send(brunoId, "você estava offline");
        await("server accepted it (✓)", () -> state(ana, brunoId, whileAway.id()) == State.SENT);

        Session brunoAgain = login(brunoHome, brunoEmail, ChatEvents.NONE);
        await("bruno receives it after login", () -> has(brunoAgain, anaId, "você estava offline"));
        assertThat(texts(brunoAgain, anaId)).as("history is local and survives restarts")
                .containsExactly("olá, bruno! 🔐", "você estava offline");
        await("ana sees ✓✓ for the offline message", () -> state(ana, brunoId, whileAway.id()) == State.DELIVERED);

        // Bruno logs in on a new device: new key. Ana must explicitly trust it before sending again.
        brunoAgain.close();
        open.remove(brunoAgain);
        Session brunoNewDevice = login(brunoSecondDevice, brunoEmail, ChatEvents.NONE);
        await("ana notices the key change", () -> contact(ana, brunoId).keyChanged());
        assertThat(anaNotices).anyMatch(n -> n.contains("mudou"));
        assertThatThrownBy(() -> ana.chat().send(brunoId, "bloqueada"))
                .isInstanceOf(UserFacingException.class).hasMessageContaining("/trust");

        assertThat(new Commands(ana).execute("/trust", brunoId).output().getFirst()).contains("aceita");
        ana.chat().send(brunoId, "nova chave ok");
        await("new device decrypts", () -> has(brunoNewDevice, anaId, "nova chave ok"));
        assertThat(texts(brunoNewDevice, anaId)).as("new device starts with empty history")
                .containsExactly("nova chave ok");
    }

    @Test
    void messagesWrittenWhileOfflineAreSentFromTheOutbox() throws Exception {
        String anaEmail = uniqueEmail();
        String brunoEmail = uniqueEmail();
        Session ana = signUp(anaHome, anaEmail, ChatEvents.NONE);
        Session bruno = signUp(brunoHome, brunoEmail, ChatEvents.NONE);
        new Commands(ana).execute("/add " + brunoEmail, null);
        await("invite", () -> !bruno.contacts().incomingInvites().isEmpty());
        new Commands(bruno).execute("/accept 1", null);
        UUID brunoId = bruno.me().id();
        await("accepted", () -> contact(ana, brunoId).fingerprint() != null);

        // Ana's process stops before the server confirms anything: simulate by sending with no realtime link.
        ana.close();
        open.remove(ana);
        Session anaOffline = new AuthService(config(anaHome)).login(anaEmail, PASSWORD, ChatEvents.NONE);
        open.add(anaOffline);
        StoredMessage queued = anaOffline.chat().send(brunoId, "escrita offline");
        assertThat(state(anaOffline, brunoId, queued.id())).isEqualTo(State.QUEUED);

        anaOffline.start();
        await("outbox flushed on connect", () -> has(bruno, ana.me().id(), "escrita offline"));
    }

    private ChatEvents recordingNotices() {
        return new ChatEvents() {
            @Override
            public void contactsChanged() {
            }

            @Override
            public void messageChanged(StoredMessage message) {
            }

            @Override
            public void notice(String text) {
                anaNotices.add(text);
            }

            @Override
            public void connectionChanged(boolean online) {
            }

            @Override
            public void sessionReplaced() {
            }
        };
    }

    private Session signUp(Path home, String email, ChatEvents events) throws Exception {
        AuthService auth = new AuthService(config(home));
        auth.register(email, PASSWORD);
        Session session = auth.verify(email, PASSWORD, latestCodeFor(email), events);
        open.add(session);
        session.start();
        return session;
    }

    private Session login(Path home, String email, ChatEvents events) throws Exception {
        Session session = new AuthService(config(home)).login(email, PASSWORD, events);
        open.add(session);
        session.start();
        return session;
    }

    private ClientConfig config(Path home) {
        return new ClientConfig(home, URI.create("http://localhost:" + port));
    }

    private static Contact contact(Session session, UUID userId) {
        return session.contacts().find(userId).orElseThrow(() -> new AssertionError("no contact " + userId));
    }

    private static boolean has(Session session, UUID contactId, String text) {
        return texts(session, contactId).contains(text);
    }

    private static List<String> texts(Session session, UUID contactId) {
        return session.chat().conversation(contactId, 100).stream().map(StoredMessage::body).toList();
    }

    private static State state(Session session, UUID contactId, UUID messageId) {
        return session.chat().conversation(contactId, 100).stream()
                .filter(m -> m.id().equals(messageId)).findFirst().map(StoredMessage::state).orElse(null);
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
            } catch (AssertionError ignored) {
                // not there yet
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting until " + what);
    }
}
