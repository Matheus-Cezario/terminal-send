package dev.terminalsend.client.store;

import dev.terminalsend.client.store.StoredMessage.State;
import dev.terminalsend.client.util.OwnerOnlyFiles;
import dev.terminalsend.protocol.rest.ConnectionDtos.Direction;
import dev.terminalsend.protocol.rest.ConnectionDtos.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class LocalStoreTest {

    @TempDir
    Path home;

    private Path file;
    private LocalStore store;
    private final UUID ana = UUID.randomUUID();

    @BeforeEach
    void open() throws Exception {
        file = home.resolve("ts-7KQ2MX/history.db");
        store = LocalStore.open(file);
        store.upsertContact(ana, "ana@x.com", "ts-AAAAAA", UUID.randomUUID(), Status.ACCEPTED, Direction.OUTGOING);
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void databaseFileIsOwnerOnly() throws Exception {
        assumeThat(OwnerOnlyFiles.posix()).isTrue();

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }

    @Test
    void upsertKeepsTrustState() {
        store.setTrustedFingerprint(ana, "fp1");
        store.markVerified(ana);

        store.upsertContact(ana, "ana@x.com", "ts-AAAAAA", UUID.randomUUID(), Status.ACCEPTED, Direction.INCOMING);

        Contact contact = store.contact(ana).orElseThrow();
        assertThat(contact.fingerprint()).isEqualTo("fp1");
        assertThat(contact.verified()).isTrue();
        assertThat(contact.direction()).isEqualTo(Direction.INCOMING);
    }

    @Test
    void pendingFingerprintIsAcceptedExplicitly() {
        store.setTrustedFingerprint(ana, "fp1");
        store.setPendingFingerprint(ana, "fp2");
        assertThat(store.contact(ana).orElseThrow().keyChanged()).isTrue();

        store.setTrustedFingerprint(ana, "fp2");

        Contact contact = store.contact(ana).orElseThrow();
        assertThat(contact.fingerprint()).isEqualTo("fp2");
        assertThat(contact.keyChanged()).isFalse();
    }

    @Test
    void keepsEveryKeySeenForAContact() {
        store.putContactKey(ana, "fp1", new byte[]{1});
        store.putContactKey(ana, "fp2", new byte[]{2});
        store.putContactKey(ana, "fp1", new byte[]{9});

        assertThat(store.contactKey(ana, "fp1")).contains(new byte[]{1});
        assertThat(store.contactKey(ana, "fp2")).contains(new byte[]{2});
        assertThat(store.contactKey(ana, "nope")).isEmpty();
    }

    @Test
    void duplicateMessagesAreIgnored() {
        StoredMessage m = incoming("oi", Instant.parse("2026-10-05T12:00:00Z"));

        assertThat(store.insertMessage(m)).isTrue();
        assertThat(store.insertMessage(m)).isFalse();
        assertThat(store.conversation(ana, 10)).hasSize(1);
    }

    @Test
    void conversationReturnsLatestMessagesOldestFirst() {
        for (int i = 0; i < 5; i++) {
            store.insertMessage(incoming("m" + i, Instant.parse("2026-10-05T12:00:00Z").plusSeconds(i)));
        }

        assertThat(store.conversation(ana, 3)).extracting(StoredMessage::body).containsExactly("m2", "m3", "m4");
    }

    @Test
    void deliveredIsNotDowngradedByALateAccepted() {
        StoredMessage m = outgoing("oi");
        store.insertMessage(m);

        store.updateState(m.id(), State.DELIVERED);
        assertThat(store.updateState(m.id(), State.SENT)).isEmpty();
        assertThat(store.message(m.id()).orElseThrow().state()).isEqualTo(State.DELIVERED);
    }

    @Test
    void outboxPreservesOrderAndClearRemovesConversation() {
        StoredMessage first = outgoing("1");
        StoredMessage second = outgoing("2");
        store.insertMessage(first);
        store.insertMessage(second);
        store.putOutbox(first.id(), "{\"a\":1}");
        store.putOutbox(second.id(), "{\"a\":2}");

        assertThat(store.outbox()).extracting(e -> e.getKey()).containsExactly(first.id(), second.id());

        store.clearConversation(ana);
        assertThat(store.outbox()).isEmpty();
        assertThat(store.conversation(ana, 10)).isEmpty();
    }

    @Test
    void retainDropsContactsButKeepsHistory() {
        store.insertMessage(incoming("oi", Instant.now()));

        store.retainContacts(List.of());

        assertThat(store.contacts()).isEmpty();
        assertThat(store.conversation(ana, 10)).hasSize(1);
    }

    @Test
    void dataSurvivesReopen() throws Exception {
        store.insertMessage(incoming("persistido", Instant.now()));
        store.close();

        store = LocalStore.open(file);

        assertThat(store.conversation(ana, 10)).extracting(StoredMessage::body).containsExactly("persistido");
    }

    private StoredMessage incoming(String body, Instant sentAt) {
        return new StoredMessage(UUID.randomUUID(), ana, StoredMessage.Direction.IN, body, sentAt, Instant.now(),
                State.RECEIVED);
    }

    private StoredMessage outgoing(String body) {
        return new StoredMessage(UUID.randomUUID(), ana, StoredMessage.Direction.OUT, body, Instant.now(), null,
                State.QUEUED);
    }
}
