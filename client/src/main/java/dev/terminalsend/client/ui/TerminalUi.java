package dev.terminalsend.client.ui;

import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import dev.terminalsend.client.ClientConfig;
import dev.terminalsend.client.app.AuthService;
import dev.terminalsend.client.app.ChatEvents;
import dev.terminalsend.client.app.Commands;
import dev.terminalsend.client.app.Session;
import dev.terminalsend.client.store.StoredMessage;

import java.io.IOException;

/** Lanterna entry point: auth screen, then the main window, until the user quits. */
public final class TerminalUi {

    private final ClientConfig config;

    public TerminalUi(ClientConfig config) {
        this.config = config;
    }

    public void run() throws IOException {
        // Never fall back to Lanterna's Swing emulator: this is a terminal app.
        DefaultTerminalFactory factory = new DefaultTerminalFactory().setForceTextTerminal(true);
        try (Screen screen = factory.createScreen()) {
            screen.startScreen();
            MultiWindowTextGUI gui = new MultiWindowTextGUI(screen);
            AuthService auth = new AuthService(config);
            while (true) {
                // Events can arrive between login and the main window opening; they're replayed from the store.
                EventRelay relay = new EventRelay();
                AuthWindow authWindow = new AuthWindow(gui, auth, config.serverUrl(), relay);
                gui.addWindowAndWait(authWindow);
                Session session = authWindow.session();
                if (session == null) {
                    return;
                }
                MainWindow main = new MainWindow(gui, session);
                relay.target = main;
                session.start();
                gui.addWindowAndWait(main);
                if (main.outcome() == Commands.Action.LOGOUT) {
                    session.logout();
                } else {
                    session.close();
                    return;
                }
            }
        }
    }

    /** Forwards to the main window once it exists; drops events before that. */
    private static final class EventRelay implements ChatEvents {

        private volatile ChatEvents target = ChatEvents.NONE;

        @Override
        public void contactsChanged() {
            target.contactsChanged();
        }

        @Override
        public void messageChanged(StoredMessage message) {
            target.messageChanged(message);
        }

        @Override
        public void notice(String text) {
            target.notice(text);
        }

        @Override
        public void connectionChanged(boolean online) {
            target.connectionChanged(online);
        }

        @Override
        public void sessionReplaced() {
            target.sessionReplaced();
        }
    }
}
