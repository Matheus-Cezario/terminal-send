package dev.terminalsend.client.ui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.BorderLayout;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.WindowListenerAdapter;
import dev.terminalsend.client.app.ChatEvents;
import dev.terminalsend.client.app.Commands;
import dev.terminalsend.client.app.Session;
import dev.terminalsend.client.store.Contact;
import dev.terminalsend.client.store.StoredMessage;
import dev.terminalsend.protocol.KeyFingerprints;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contacts on the left, conversation on the right, input at the bottom (architecture §4). */
final class MainWindow extends BasicWindow implements ChatEvents {

    private static final int HISTORY_LIMIT = 500;

    private final WindowBasedTextGUI gui;
    private final Session session;
    private final Commands commands;
    private final ZoneId zone = ZoneId.systemDefault();
    private final ActionListBox contactList = new ActionListBox(new TerminalSize(30, 10));
    private final Label header = new Label("");
    private final TextBox history = new TextBox(new TerminalSize(60, 10), TextBox.Style.MULTI_LINE);
    private final InputBox input = new InputBox(this::onSubmit);
    private final Label status = new Label("");
    private final Map<UUID, Integer> unread = new HashMap<>();
    private final List<String> systemLines = new ArrayList<>();
    private UUID current;
    private boolean online;
    private Commands.Action outcome = Commands.Action.QUIT;

    MainWindow(WindowBasedTextGUI gui, Session session) {
        super("terminal-send");
        this.gui = gui;
        this.session = session;
        this.commands = new Commands(session);
        setHints(List.of(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS));
        history.setReadOnly(true);

        Panel left = new Panel(new BorderLayout());
        contactList.setLayoutData(BorderLayout.Location.CENTER);
        left.addComponent(contactList);

        Panel chat = new Panel(new BorderLayout());
        header.setLayoutData(BorderLayout.Location.TOP);
        history.setLayoutData(BorderLayout.Location.CENTER);
        chat.addComponent(header);
        chat.addComponent(history);
        chat.addComponent(input.withBorder(Borders.singleLine("mensagem ou /help")).setLayoutData(
                BorderLayout.Location.BOTTOM));

        Panel root = new Panel(new BorderLayout());
        root.addComponent(left.withBorder(Borders.singleLine("Contatos")).setLayoutData(BorderLayout.Location.LEFT));
        root.addComponent(chat.withBorder(Borders.singleLine()).setLayoutData(BorderLayout.Location.CENTER));
        root.addComponent(status.setLayoutData(BorderLayout.Location.BOTTOM));
        setComponent(root);

        systemLines.add("Bem-vindo, " + session.me().email() + "! Seu ID é " + session.me().handle() + ".");
        systemLines.add("Use /add <email|ID> para convidar alguém, Tab para escolher um contato e /help para ajuda.");
        refreshContacts();
        render();
        updateStatus();
        setFocusedInteractable(input);
        addWindowListener(new WindowListenerAdapter() {
            @Override
            public void onResized(Window window, TerminalSize oldSize, TerminalSize newSize) {
                render();
            }
        });
    }

    /** What the user asked for when the window closed. */
    Commands.Action outcome() {
        return outcome;
    }

    // --- ChatEvents: called from background threads ---

    @Override
    public void contactsChanged() {
        later(this::refreshContacts);
    }

    @Override
    public void messageChanged(StoredMessage message) {
        later(() -> {
            if (message.contactId().equals(current)) {
                render();
            } else if (message.direction() == StoredMessage.Direction.IN) {
                unread.merge(message.contactId(), 1, Integer::sum);
                refreshContacts();
            }
        });
    }

    @Override
    public void notice(String text) {
        later(() -> {
            systemLines.add(text);
            render();
        });
    }

    @Override
    public void connectionChanged(boolean isOnline) {
        later(() -> {
            online = isOnline;
            updateStatus();
        });
    }

    @Override
    public void sessionReplaced() {
        later(() -> {
            online = false;
            systemLines.add("Sua conta foi aberta em outro terminal; esta sessão parou de receber mensagens.");
            render();
            status.setText(" desconectado: sessão aberta em outro lugar · /logout ou /quit");
        });
    }

    // --- UI logic (GUI thread) ---

    private void onSubmit(String text) {
        Commands.Result result = commands.execute(text, current);
        systemLines.addAll(result.output());
        switch (result.action()) {
            case SELECT -> select(result.select());
            case LOGOUT, QUIT -> {
                outcome = result.action();
                close();
                return;
            }
            default -> {
            }
        }
        refreshContacts();
        render();
    }

    private void select(UUID contactId) {
        current = contactId;
        unread.remove(contactId);
        systemLines.clear();
        session.contacts().find(contactId).ifPresent(contact -> {
            if (contact.isIncomingInvite()) {
                int n = session.contacts().incomingInvites().indexOf(contact) + 1;
                systemLines.add("Convite de " + contact.email() + ". Use /accept " + n + " ou /reject " + n + ".");
            } else if (!contact.isAccepted()) {
                systemLines.add("Aguardando " + contact.email() + " aceitar seu convite.");
            } else if (contact.keyChanged()) {
                systemLines.add("⚠ A chave deste contato mudou. Confira com /verify e use /trust.");
            }
        });
        refreshContacts();
        render();
        setFocusedInteractable(input);
    }

    private void refreshContacts() {
        int selected = contactList.getSelectedIndex();
        contactList.clearItems();
        for (Contact contact : session.contacts().all()) {
            contactList.addItem(label(contact), () -> select(contact.userId()));
        }
        if (contactList.getItemCount() > 0) {
            contactList.setSelectedIndex(Math.max(0, Math.min(selected, contactList.getItemCount() - 1)));
        }
        updateHeader();
    }

    private String label(Contact contact) {
        String marker;
        String suffix = "";
        if (contact.isIncomingInvite()) {
            marker = "?";
            suffix = " (convite)";
        } else if (!contact.isAccepted()) {
            marker = "…";
            suffix = " (pendente)";
        } else {
            marker = contact.keyChanged() ? "⚠" : contact.userId().equals(current) ? "▸" : "●";
            int count = unread.getOrDefault(contact.userId(), 0);
            suffix = count > 0 ? " (" + count + ")" : "";
        }
        return marker + " " + contact.displayName() + suffix;
    }

    private void updateHeader() {
        if (current == null) {
            header.setText(" Nenhuma conversa selecionada");
            return;
        }
        header.setText(session.contacts().find(current)
                .map(c -> " " + c.email() + " (" + c.handle() + ")"
                        + (c.fingerprint() == null ? "" : " · fp " + KeyFingerprints.display(c.fingerprint())
                        .substring(0, 9) + "…") + (c.verified() ? " ✔" : ""))
                .orElse(" Contato removido"));
    }

    private void render() {
        int width = Math.max(20, historyWidth() - 2);
        List<String> lines = new ArrayList<>();
        if (current != null) {
            String peer = session.contacts().find(current).map(Contact::displayName).orElse("contato");
            for (StoredMessage m : session.chat().conversation(current, HISTORY_LIMIT)) {
                lines.addAll(MessageLines.wrap(MessageLines.format(m, peer, zone), width));
            }
        }
        for (String line : systemLines) {
            lines.addAll(MessageLines.wrap("· " + line, width));
        }
        history.setText(String.join("\n", lines));
        history.setCaretPosition(Math.max(0, history.getLineCount() - 1), 0);
        updateHeader();
    }

    /** Before the first layout the text box has no size yet; estimate from the terminal instead. */
    private int historyWidth() {
        int laidOut = history.getSize().getColumns();
        if (laidOut > 0) {
            return laidOut;
        }
        return gui.getScreen().getTerminalSize().getColumns() - contactList.getPreferredSize().getColumns() - 6;
    }

    private void updateStatus() {
        status.setText(" " + (online ? "● online" : "○ conectando…") + " · " + session.me().handle()
                + " · seu fp " + KeyFingerprints.display(session.fingerprint()).substring(0, 9)
                + "… · Tab: contatos/chat · /help · Ctrl+C sai");
    }

    private void later(Runnable action) {
        gui.getGUIThread().invokeLater(action);
    }
}
