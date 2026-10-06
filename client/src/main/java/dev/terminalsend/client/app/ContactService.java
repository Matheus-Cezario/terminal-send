package dev.terminalsend.client.app;

import dev.terminalsend.client.net.ApiClient;
import dev.terminalsend.client.store.Contact;
import dev.terminalsend.client.store.LocalStore;
import dev.terminalsend.protocol.KeyFingerprints;
import dev.terminalsend.protocol.rest.ConnectionDtos.ConnectionView;
import dev.terminalsend.protocol.rest.KeyDtos.PublicKeyView;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Mirrors server connections into the local store and manages contact keys with trust-on-first-use. */
public final class ContactService {

    private final ApiClient api;
    private final LocalStore store;
    private final ChatEvents events;

    ContactService(ApiClient api, LocalStore store, ChatEvents events) {
        this.api = api;
        this.store = store;
        this.events = events;
    }

    public List<Contact> all() {
        return store.contacts();
    }

    public Optional<Contact> find(UUID userId) {
        return store.contact(userId);
    }

    /** Incoming invites in a stable order; {@code /accept n} and {@code /invites} both use it. */
    public List<Contact> incomingInvites() {
        return store.contacts().stream().filter(Contact::isIncomingInvite).toList();
    }

    public synchronized void sync() {
        List<ConnectionView> views = api.connections();
        for (ConnectionView v : views) {
            store.upsertContact(v.peer().id(), v.peer().email(), v.peer().handle(), v.id(), v.status(), v.direction());
        }
        store.retainContacts(views.stream().map(v -> v.peer().id()).toList());
        for (Contact contact : store.contacts()) {
            if (contact.isAccepted() && contact.fingerprint() == null) {
                refreshKey(contact.userId());
            }
        }
        events.contactsChanged();
    }

    /**
     * Fetches the contact's current key. The first key is trusted automatically; a different one later is only
     * recorded as pending until the user runs {@code /trust} (it is still usable to decrypt what they send).
     */
    public synchronized Optional<String> refreshKey(UUID userId) {
        Optional<PublicKeyView> view = api.publicKey(userId);
        Optional<Contact> contact = store.contact(userId);
        if (view.isEmpty() || contact.isEmpty()) {
            return Optional.empty();
        }
        byte[] raw = Base64.getDecoder().decode(view.get().publicKey());
        String fingerprint = KeyFingerprints.of(raw);
        store.putContactKey(userId, fingerprint, raw);
        String trusted = contact.get().fingerprint();
        if (trusted == null) {
            store.setTrustedFingerprint(userId, fingerprint);
        } else if (!trusted.equals(fingerprint) && !fingerprint.equals(contact.get().pendingFingerprint())) {
            store.setPendingFingerprint(userId, fingerprint);
            events.notice("⚠ A chave de " + contact.get().email() + " mudou (novo dispositivo?). Confira com /verify "
                    + "e use /trust para voltar a enviar.");
        }
        events.contactsChanged();
        return Optional.of(fingerprint);
    }

    public void trust(UUID userId) {
        Contact contact = require(userId);
        if (!contact.keyChanged()) {
            throw new UserFacingException("A chave de " + contact.email() + " não mudou.");
        }
        store.setTrustedFingerprint(userId, contact.pendingFingerprint());
        events.contactsChanged();
    }

    public void markVerified(UUID userId) {
        require(userId);
        store.markVerified(userId);
        events.contactsChanged();
    }

    public Optional<byte[]> keyFor(UUID userId, String fingerprint) {
        return store.contactKey(userId, fingerprint);
    }

    public void invite(String target) {
        api.invite(target);
        sync();
    }

    public void accept(Contact invite) {
        api.accept(invite.connectionId());
        sync();
    }

    public void reject(Contact invite) {
        api.reject(invite.connectionId());
        sync();
    }

    public void remove(Contact contact) {
        api.remove(contact.connectionId());
        sync();
    }

    private Contact require(UUID userId) {
        return store.contact(userId).orElseThrow(() -> new UserFacingException("Contato não encontrado."));
    }
}
