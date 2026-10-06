package dev.terminalsend.client.store;

import dev.terminalsend.client.store.StoredMessage.State;
import dev.terminalsend.client.util.OwnerOnlyFiles;
import dev.terminalsend.protocol.rest.ConnectionDtos.Direction;
import dev.terminalsend.protocol.rest.ConnectionDtos.Status;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only copy of the conversation history (tech-spec RF6): one SQLite file per account, owner-only.
 * Single connection guarded by {@code synchronized}; UI and WebSocket threads both write here.
 */
public final class LocalStore implements AutoCloseable {

    private static final int SCHEMA_VERSION = 1;

    private final Connection db;

    private LocalStore(Connection db) {
        this.db = db;
    }

    public static LocalStore open(Path file) throws IOException {
        OwnerOnlyFiles.touch(file);
        try {
            Connection db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            LocalStore store = new LocalStore(db);
            store.migrate();
            return store;
        } catch (SQLException e) {
            throw new IOException("Cannot open local history " + file, e);
        }
    }

    private void migrate() throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA journal_mode = WAL");
            int version;
            try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                version = rs.getInt(1);
            }
            if (version < 1) {
                st.execute("""
                        CREATE TABLE contacts (
                            user_id TEXT PRIMARY KEY,
                            email TEXT NOT NULL,
                            handle TEXT NOT NULL,
                            connection_id TEXT NOT NULL,
                            status TEXT NOT NULL,
                            direction TEXT NOT NULL,
                            fingerprint TEXT,
                            pending_fingerprint TEXT,
                            fingerprint_verified INTEGER NOT NULL DEFAULT 0
                        )""");
                st.execute("""
                        CREATE TABLE contact_keys (
                            user_id TEXT NOT NULL,
                            fingerprint TEXT NOT NULL,
                            public_key BLOB NOT NULL,
                            first_seen INTEGER NOT NULL,
                            PRIMARY KEY (user_id, fingerprint)
                        )""");
                st.execute("""
                        CREATE TABLE messages (
                            id TEXT PRIMARY KEY,
                            contact_id TEXT NOT NULL,
                            direction TEXT NOT NULL,
                            body TEXT NOT NULL,
                            sent_at INTEGER NOT NULL,
                            received_at INTEGER,
                            state TEXT NOT NULL
                        )""");
                st.execute("CREATE INDEX messages_contact_idx ON messages (contact_id, sent_at)");
                st.execute("""
                        CREATE TABLE outbox (
                            message_id TEXT PRIMARY KEY,
                            envelope_json TEXT NOT NULL,
                            created_at INTEGER NOT NULL
                        )""");
                st.execute("CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                st.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            }
        }
    }

    // --- contacts ---

    /** Inserts or updates the connection fields, leaving key/TOFU state untouched. */
    public synchronized void upsertContact(UUID userId, String email, String handle, UUID connectionId,
                                           Status status, Direction direction) {
        update("""
                INSERT INTO contacts (user_id, email, handle, connection_id, status, direction)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET email = excluded.email, handle = excluded.handle,
                    connection_id = excluded.connection_id, status = excluded.status, direction = excluded.direction
                """, userId.toString(), email, handle, connectionId.toString(), status.name(), direction.name());
    }

    /** Drops contacts the server no longer lists (removed or cancelled); their messages stay in history. */
    public synchronized void retainContacts(Collection<UUID> keep) {
        for (Contact contact : contacts()) {
            if (!keep.contains(contact.userId())) {
                update("DELETE FROM contact_keys WHERE user_id = ?", contact.userId().toString());
                update("DELETE FROM contacts WHERE user_id = ?", contact.userId().toString());
            }
        }
    }

    public synchronized List<Contact> contacts() {
        return query("""
                SELECT * FROM contacts
                ORDER BY CASE status WHEN 'ACCEPTED' THEN 0 ELSE 1 END, email
                """, this::contact);
    }

    public synchronized Optional<Contact> contact(UUID userId) {
        return query("SELECT * FROM contacts WHERE user_id = ?", this::contact, userId.toString()).stream().findFirst();
    }

    public synchronized void putContactKey(UUID userId, String fingerprint, byte[] publicKey) {
        update("""
                INSERT INTO contact_keys (user_id, fingerprint, public_key, first_seen) VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, userId.toString(), fingerprint, publicKey, Instant.now().toEpochMilli());
    }

    /** Old keys are kept so envelopes sealed before a key change can still be opened. */
    public synchronized Optional<byte[]> contactKey(UUID userId, String fingerprint) {
        return query("SELECT public_key FROM contact_keys WHERE user_id = ? AND fingerprint = ?",
                rs -> rs.getBytes(1), userId.toString(), fingerprint).stream().findFirst();
    }

    public synchronized void setTrustedFingerprint(UUID userId, String fingerprint) {
        update("UPDATE contacts SET fingerprint = ?, pending_fingerprint = NULL, fingerprint_verified = 0 "
                + "WHERE user_id = ?", fingerprint, userId.toString());
    }

    public synchronized void setPendingFingerprint(UUID userId, String fingerprint) {
        update("UPDATE contacts SET pending_fingerprint = ? WHERE user_id = ?", fingerprint, userId.toString());
    }

    public synchronized void markVerified(UUID userId) {
        update("UPDATE contacts SET fingerprint_verified = 1 WHERE user_id = ?", userId.toString());
    }

    // --- messages ---

    /** Returns false when the id already exists (at-least-once delivery means duplicates are normal). */
    public synchronized boolean insertMessage(StoredMessage m) {
        return update("""
                INSERT INTO messages (id, contact_id, direction, body, sent_at, received_at, state)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, m.id().toString(), m.contactId().toString(), m.direction().name(), m.body(),
                m.sentAt().toEpochMilli(), m.receivedAt() == null ? null : m.receivedAt().toEpochMilli(),
                m.state().name()) > 0;
    }

    public synchronized Optional<StoredMessage> message(UUID id) {
        return query("SELECT * FROM messages WHERE id = ?", this::message, id.toString()).stream().findFirst();
    }

    /** Returns the updated message, unless it would move backwards (e.g. a late SENT after DELIVERED). */
    public synchronized Optional<StoredMessage> updateState(UUID id, State state) {
        Optional<StoredMessage> current = message(id);
        if (current.isEmpty() || (current.get().state() == State.DELIVERED && state == State.SENT)) {
            return Optional.empty();
        }
        update("UPDATE messages SET state = ? WHERE id = ?", state.name(), id.toString());
        return Optional.of(current.get().withState(state));
    }

    /** Most recent {@code limit} messages of a conversation, oldest first. */
    public synchronized List<StoredMessage> conversation(UUID contactId, int limit) {
        List<StoredMessage> newestFirst = query("""
                SELECT * FROM messages WHERE contact_id = ? ORDER BY sent_at DESC, id DESC LIMIT ?
                """, this::message, contactId.toString(), limit);
        List<StoredMessage> ordered = new ArrayList<>(newestFirst);
        Collections.reverse(ordered);
        return ordered;
    }

    public synchronized List<StoredMessage> outgoingInStates(UUID contactId, Collection<State> states) {
        return conversation(contactId, Integer.MAX_VALUE).stream()
                .filter(m -> m.direction() == StoredMessage.Direction.OUT && states.contains(m.state()))
                .toList();
    }

    public synchronized void clearConversation(UUID contactId) {
        update("DELETE FROM outbox WHERE message_id IN (SELECT id FROM messages WHERE contact_id = ?)",
                contactId.toString());
        update("DELETE FROM messages WHERE contact_id = ?", contactId.toString());
    }

    // --- outbox ---

    public synchronized void putOutbox(UUID messageId, String envelopeJson) {
        // Upsert (not INSERT OR REPLACE) so a re-sealed envelope keeps its rowid and its place in line.
        update("""
                INSERT INTO outbox (message_id, envelope_json, created_at) VALUES (?, ?, ?)
                ON CONFLICT (message_id) DO UPDATE SET envelope_json = excluded.envelope_json
                """, messageId.toString(), envelopeJson, Instant.now().toEpochMilli());
    }

    public synchronized void removeOutbox(UUID messageId) {
        update("DELETE FROM outbox WHERE message_id = ?", messageId.toString());
    }

    /** Pending envelopes in creation order, keyed by message id. */
    public synchronized List<Map.Entry<UUID, String>> outbox() {
        return query("SELECT message_id, envelope_json FROM outbox ORDER BY rowid",
                rs -> Map.entry(UUID.fromString(rs.getString(1)), rs.getString(2)));
    }

    @Override
    public synchronized void close() {
        try {
            db.close();
        } catch (SQLException ignored) {
            // closing anyway
        }
    }

    // --- plumbing ---

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private Contact contact(ResultSet rs) throws SQLException {
        return new Contact(UUID.fromString(rs.getString("user_id")), rs.getString("email"), rs.getString("handle"),
                UUID.fromString(rs.getString("connection_id")), Status.valueOf(rs.getString("status")),
                Direction.valueOf(rs.getString("direction")), rs.getString("fingerprint"),
                rs.getString("pending_fingerprint"), rs.getInt("fingerprint_verified") == 1);
    }

    private StoredMessage message(ResultSet rs) throws SQLException {
        long received = rs.getLong("received_at");
        Instant receivedAt = rs.wasNull() ? null : Instant.ofEpochMilli(received);
        return new StoredMessage(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("contact_id")),
                StoredMessage.Direction.valueOf(rs.getString("direction")), rs.getString("body"),
                Instant.ofEpochMilli(rs.getLong("sent_at")), receivedAt, State.valueOf(rs.getString("state")));
    }

    private int update(String sql, Object... params) {
        try (PreparedStatement st = prepare(sql, params)) {
            return st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Local store write failed", e);
        }
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement st = prepare(sql, params); ResultSet rs = st.executeQuery()) {
            List<T> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapper.map(rs));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Local store read failed", e);
        }
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement st = db.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            st.setObject(i + 1, params[i]);
        }
        return st;
    }
}
