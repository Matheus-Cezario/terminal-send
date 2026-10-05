package dev.terminalsend.client.ui;

import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import dev.terminalsend.client.ClientConfig;

import java.io.IOException;
import java.util.List;

/** Lanterna entry point. For now a placeholder window; login, contacts and chat screens come in M6. */
public final class TerminalUi {

    private final ClientConfig config;

    public TerminalUi(ClientConfig config) {
        this.config = config;
    }

    public void run() throws IOException {
        try (Screen screen = new DefaultTerminalFactory().createScreen()) {
            screen.startScreen();
            MultiWindowTextGUI gui = new MultiWindowTextGUI(screen);

            BasicWindow window = new BasicWindow("terminal-send");
            window.setHints(List.of(Window.Hint.CENTERED));
            Panel panel = new Panel();
            panel.addComponent(new Label("Servidor: " + config.serverUrl()));
            panel.addComponent(new Label("Em construção — veja docs/tech-spec.md"));
            panel.addComponent(new Button("Sair", window::close));
            window.setComponent(panel);

            gui.addWindowAndWait(window);
        }
    }
}
