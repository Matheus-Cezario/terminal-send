package dev.terminalsend.client.app;

import dev.terminalsend.client.net.ApiError;
import dev.terminalsend.client.store.Contact;
import dev.terminalsend.protocol.KeyFingerprints;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Parses what the user types in the input box. Plain text goes to the selected contact. */
public final class Commands {

    public enum Action { NONE, SELECT, LOGOUT, QUIT }

    /** {@code output} lines are shown in the chat pane; {@code select} is the contact to switch to, if any. */
    public record Result(List<String> output, Action action, UUID select) {

        static Result say(String... lines) {
            return new Result(List.of(lines), Action.NONE, null);
        }

        static Result silent() {
            return new Result(List.of(), Action.NONE, null);
        }
    }

    static final String HELP = """
            Comandos:
              /add <email|ts-ID>   convidar alguém
              /invites             listar convites recebidos
              /accept <n>          aceitar o convite n   ·  /reject <n> recusar
              /verify              mostrar fingerprints para comparar com o contato ( /verify ok marca como conferido )
              /trust               aceitar a nova chave do contato atual e reenviar o que falhou
              /remove              remover o contato atual
              /clear               apagar o histórico local desta conversa
              /me                  mostrar seu email, ID e fingerprint
              /logout · /quit
            Tab alterna entre a lista de contatos e o campo de texto.""";

    private final Session session;

    public Commands(Session session) {
        this.session = session;
    }

    public Result execute(String line, UUID current) {
        String input = line.strip();
        if (input.isEmpty()) {
            return Result.silent();
        }
        try {
            if (!input.startsWith("/")) {
                if (current == null) {
                    return Result.say("Selecione um contato na lista (Tab) ou use /add para convidar alguém.");
                }
                session.chat().send(current, input);
                return Result.silent();
            }
            String[] parts = input.split("\\s+", 2);
            String arg = parts.length > 1 ? parts[1].strip() : "";
            return switch (parts[0].toLowerCase()) {
                case "/help", "/?" -> Result.say(HELP.split("\n"));
                case "/add" -> add(arg);
                case "/invites" -> invites();
                case "/accept" -> accept(arg);
                case "/reject" -> reject(arg);
                case "/verify" -> verify(arg, current);
                case "/trust" -> trust(current);
                case "/remove" -> remove(current);
                case "/clear" -> clear(current);
                case "/me" -> Result.say("Você: " + session.me().email() + " · " + session.me().handle(),
                        "Seu fingerprint: " + KeyFingerprints.display(session.fingerprint()));
                case "/logout" -> new Result(List.of(), Action.LOGOUT, null);
                case "/quit", "/exit" -> new Result(List.of(), Action.QUIT, null);
                default -> Result.say("Comando desconhecido: " + parts[0] + " (veja /help)");
            };
        } catch (UserFacingException e) {
            return Result.say(e.getMessage());
        } catch (ApiError e) {
            return Result.say("Erro: " + e.getMessage());
        }
    }

    private Result add(String target) {
        if (target.isEmpty()) {
            return Result.say("Uso: /add <email|ts-ID>");
        }
        session.contacts().invite(target);
        return Result.say("Convite enviado para " + target + ". Vocês poderão conversar quando for aceito.");
    }

    private Result invites() {
        List<Contact> invites = session.contacts().incomingInvites();
        if (invites.isEmpty()) {
            return Result.say("Nenhum convite pendente.");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Convites recebidos:");
        for (int i = 0; i < invites.size(); i++) {
            lines.add("  " + (i + 1) + ". " + invites.get(i).email() + " (" + invites.get(i).handle() + ")");
        }
        lines.add("Use /accept <n> ou /reject <n>.");
        return new Result(lines, Action.NONE, null);
    }

    private Result accept(String arg) {
        Contact invite = inviteAt(arg);
        session.contacts().accept(invite);
        return new Result(List.of("Agora você e " + invite.email() + " estão conectados."), Action.SELECT,
                invite.userId());
    }

    private Result reject(String arg) {
        Contact invite = inviteAt(arg);
        session.contacts().reject(invite);
        return Result.say("Convite de " + invite.email() + " recusado.");
    }

    private Result verify(String arg, UUID current) {
        Contact contact = selected(current);
        if ("ok".equalsIgnoreCase(arg)) {
            session.contacts().markVerified(contact.userId());
            return Result.say("Fingerprint de " + contact.email() + " marcado como conferido.");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Seu fingerprint:     " + KeyFingerprints.display(session.fingerprint()));
        lines.add("Fingerprint de " + contact.displayName() + ": "
                + (contact.fingerprint() == null ? "(sem chave)" : KeyFingerprints.display(contact.fingerprint()))
                + (contact.verified() ? "  ✔ conferido" : ""));
        if (contact.keyChanged()) {
            lines.add("Nova chave anunciada: " + KeyFingerprints.display(contact.pendingFingerprint()));
        }
        lines.add("Compare por outro canal (pessoalmente, ligação) e rode /verify ok se baterem.");
        return new Result(lines, Action.NONE, null);
    }

    private Result trust(UUID current) {
        Contact contact = selected(current);
        session.contacts().trust(contact.userId());
        int resent = session.chat().resendUndelivered(contact.userId());
        return Result.say("Nova chave de " + contact.email() + " aceita."
                + (resent > 0 ? " Reenviando " + resent + " mensagem(ns)." : ""));
    }

    private Result remove(UUID current) {
        Contact contact = selected(current);
        session.contacts().remove(contact);
        return Result.say(contact.email() + " removido dos contatos. O histórico local foi mantido.");
    }

    private Result clear(UUID current) {
        Contact contact = selected(current);
        session.chat().clear(contact.userId());
        return Result.say("Histórico local com " + contact.email() + " apagado.");
    }

    private Contact selected(UUID current) {
        return Optional.ofNullable(current).flatMap(id -> session.contacts().find(id))
                .orElseThrow(() -> new UserFacingException("Selecione um contato primeiro."));
    }

    private Contact inviteAt(String arg) {
        List<Contact> invites = session.contacts().incomingInvites();
        if (invites.isEmpty()) {
            throw new UserFacingException("Nenhum convite pendente.");
        }
        int index;
        try {
            index = arg.isEmpty() && invites.size() == 1 ? 1 : Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            throw new UserFacingException("Uso: /accept <n> (veja /invites)");
        }
        if (index < 1 || index > invites.size()) {
            throw new UserFacingException("Convite " + arg + " não existe (veja /invites).");
        }
        return invites.get(index - 1);
    }
}
