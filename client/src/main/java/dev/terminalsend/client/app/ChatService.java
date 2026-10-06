package dev.terminalsend.client.app;

import dev.terminalsend.client.crypto.DecryptionException;
import dev.terminalsend.client.crypto.EnvelopeCipher;
import dev.terminalsend.client.crypto.EnvelopeCipher.Sealed;
import dev.terminalsend.client.crypto.PairKeys;
import dev.terminalsend.client.crypto.X25519;
import dev.terminalsend.client.store.Contact;
import dev.terminalsend.client.store.LocalStore;
import dev.terminalsend.client.store.StoredMessage;
import dev.terminalsend.client.store.StoredMessage.State;
import dev.terminalsend.client.util.UuidV7;
import dev.terminalsend.protocol.rest.ErrorCode;
import dev.terminalsend.protocol.ws.ClientFrame;
import dev.terminalsend.protocol.ws.ClientFrame.MessageAck;
import dev.terminalsend.protocol.ws.ClientFrame.MessageSend;
import dev.terminalsend.protocol.ws.ServerFrame;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.SecretKey;
import java.security.InvalidKeyException;
import java.security.interfaces.XECPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Sends and receives messages. Every outgoing message is written to history and the outbox before it hits the
 * network, so nothing is lost while offline; the server's frames then move it through SENT and DELIVERED.
 */
public final class ChatService {

    private final UUID me;
    private final XECPrivateKey myKey;
    private final LocalStore store;
    private final ContactService contacts;
    private final ChatEvents events;
    private final JsonMapper json;
    private final Clock clock;
    private final Map<String, SecretKey> pairKeys = new ConcurrentHashMap<>();
    private volatile Predicate<ClientFrame> transport = frame -> false;

    ChatService(UUID me, XECPrivateKey myKey, LocalStore store, ContactService contacts, ChatEvents events,
                JsonMapper json, Clock clock) {
        this.me = me;
        this.myKey = myKey;
        this.store = store;
        this.contacts = contacts;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    void useTransport(Predicate<ClientFrame> transport) {
        this.transport = transport;
    }

    public List<StoredMessage> conversation(UUID contactId, int limit) {
        return store.conversation(contactId, limit);
    }

    public StoredMessage send(UUID contactId, String text) {
        String body = text.strip();
        if (body.isEmpty()) {
            throw new UserFacingException("Mensagem vazia.");
        }
        Contact contact = sendableContact(contactId);
        StoredMessage message = new StoredMessage(UuidV7.generate(clock), contactId, StoredMessage.Direction.OUT,
                body, clock.instant(), null, State.QUEUED);
        store.insertMessage(message);
        MessageSend envelope = seal(message, contact);
        store.putOutbox(message.id(), json.writeValueAsString(envelope));
        events.messageChanged(message);
        transport.test(envelope);
        return message;
    }

    /** After {@code /trust}: re-encrypt queued and failed messages for the newly trusted key and send again. */
    public int resendUndelivered(UUID contactId) {
        Contact contact = sendableContact(contactId);
        List<StoredMessage> retry = store.outgoingInStates(contactId, EnumSet.of(State.QUEUED, State.FAILED));
        for (StoredMessage message : retry) {
            MessageSend envelope = seal(message, contact);
            store.putOutbox(message.id(), json.writeValueAsString(envelope));
            store.updateState(message.id(), State.QUEUED).ifPresent(events::messageChanged);
            transport.test(envelope);
        }
        return retry.size();
    }

    public void clear(UUID contactId) {
        store.clearConversation(contactId);
    }

    /** Called on (re)connect: whatever the server never confirmed goes out again with the same id. */
    void flushOutbox() {
        for (Map.Entry<UUID, String> entry : store.outbox()) {
            try {
                if (!transport.test(json.readValue(entry.getValue(), MessageSend.class))) {
                    return;
                }
            } catch (JacksonException e) {
                store.removeOutbox(entry.getKey());
            }
        }
    }

    void handle(ServerFrame frame) {
        switch (frame) {
            case ServerFrame.MessageAccepted accepted -> {
                store.removeOutbox(accepted.id());
                store.updateState(accepted.id(), State.SENT).ifPresent(events::messageChanged);
            }
            case ServerFrame.MessageDelivered delivered -> {
                store.removeOutbox(delivered.id());
                store.updateState(delivered.id(), State.DELIVERED).ifPresent(events::messageChanged);
            }
            case ServerFrame.MessageRejected rejected -> onRejected(rejected);
            case ServerFrame.MessageDeliver deliver -> onDeliver(deliver);
            default -> {
            }
        }
    }

    private void onRejected(ServerFrame.MessageRejected rejected) {
        if (rejected.id() == null) {
            return;
        }
        store.removeOutbox(rejected.id());
        Optional<StoredMessage> failed = store.updateState(rejected.id(), State.FAILED);
        failed.ifPresent(events::messageChanged);
        if (rejected.code() == ErrorCode.KEY_MISMATCH) {
            failed.ifPresent(m -> contacts.refreshKey(m.contactId()));
        } else {
            events.notice("Mensagem não enviada: " + describe(rejected.code()));
        }
    }

    private void onDeliver(ServerFrame.MessageDeliver deliver) {
        if (store.message(deliver.id()).isEmpty()) {
            StoredMessage received = decrypt(deliver);
            if (store.insertMessage(received)) {
                events.messageChanged(received);
            }
        }
        // ACK only after the message is safely on disk (or known to be a duplicate).
        transport.test(new MessageAck(List.of(deliver.id())));
    }

    private StoredMessage decrypt(ServerFrame.MessageDeliver deliver) {
        Instant receivedAt = clock.instant();
        String body;
        Instant sentAt = deliver.sentAt() == null ? receivedAt : deliver.sentAt();
        Optional<byte[]> senderKey = contacts.keyFor(deliver.from(), deliver.senderKeyFp());
        if (senderKey.isEmpty()) {
            contacts.refreshKey(deliver.from());
            senderKey = contacts.keyFor(deliver.from(), deliver.senderKeyFp());
        }
        if (senderKey.isEmpty()) {
            body = "[não foi possível decifrar: chave do remetente desconhecida]";
        } else {
            try {
                Base64.Decoder b64 = Base64.getDecoder();
                byte[] plain = EnvelopeCipher.open(pairKey(deliver.from(), senderKey.get()), deliver.id(),
                        deliver.from(), me, new Sealed(b64.decode(deliver.nonce()), b64.decode(deliver.ciphertext())));
                MessagePayload payload = json.readValue(plain, MessagePayload.class);
                body = payload.body();
                sentAt = payload.sentAt();
            } catch (DecryptionException | InvalidKeyException | IllegalArgumentException | JacksonException e) {
                body = "[mensagem corrompida ou adulterada]";
            }
        }
        return new StoredMessage(deliver.id(), deliver.from(), StoredMessage.Direction.IN, body, sentAt, receivedAt,
                State.RECEIVED);
    }

    private MessageSend seal(StoredMessage message, Contact contact) {
        byte[] peerKey = contacts.keyFor(contact.userId(), contact.fingerprint())
                .orElseThrow(() -> new UserFacingException("Chave de " + contact.email() + " indisponível."));
        try {
            byte[] plaintext = json.writeValueAsBytes(new MessagePayload(message.body(), message.sentAt()));
            Sealed sealed = EnvelopeCipher.seal(pairKey(contact.userId(), peerKey), message.id(), me,
                    contact.userId(), plaintext);
            Base64.Encoder b64 = Base64.getEncoder();
            return new MessageSend(message.id(), contact.userId(), contact.fingerprint(),
                    b64.encodeToString(sealed.nonce()), b64.encodeToString(sealed.ciphertext()), message.sentAt());
        } catch (InvalidKeyException e) {
            throw new UserFacingException("Chave de " + contact.email() + " inválida.");
        }
    }

    private SecretKey pairKey(UUID peer, byte[] peerRawKey) throws InvalidKeyException {
        String cacheKey = peer + "|" + Base64.getEncoder().encodeToString(peerRawKey);
        SecretKey cached = pairKeys.get(cacheKey);
        if (cached == null) {
            cached = PairKeys.derive(myKey, me, X25519.decodePublic(peerRawKey), peer);
            pairKeys.put(cacheKey, cached);
        }
        return cached;
    }

    private Contact sendableContact(UUID contactId) {
        Contact contact = contacts.find(contactId)
                .orElseThrow(() -> new UserFacingException("Selecione um contato."));
        if (!contact.isAccepted()) {
            throw new UserFacingException(contact.email() + " ainda não aceitou o convite.");
        }
        if (contact.keyChanged()) {
            throw new UserFacingException("A chave de " + contact.email() + " mudou. Confira com /verify e use /trust.");
        }
        if (contact.fingerprint() == null) {
            contacts.refreshKey(contactId);
            contact = contacts.find(contactId).orElseThrow();
            if (contact.fingerprint() == null) {
                throw new UserFacingException(contact.email() + " ainda não publicou uma chave (nunca fez login).");
            }
        }
        return contact;
    }

    private static String describe(ErrorCode code) {
        if (code == null) {
            return "erro desconhecido";
        }
        return switch (code) {
            case NOT_CONNECTED -> "vocês não estão conectados";
            case TOO_LARGE -> "mensagem grande demais";
            case RATE_LIMITED -> "muitas mensagens por segundo";
            default -> code.name();
        };
    }
}
