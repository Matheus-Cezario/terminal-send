package dev.terminalsend.client.ui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import dev.terminalsend.client.app.AuthService;
import dev.terminalsend.client.app.ChatEvents;
import dev.terminalsend.client.app.Session;
import dev.terminalsend.client.app.UserFacingException;
import dev.terminalsend.client.net.ApiError;
import dev.terminalsend.protocol.rest.ErrorCode;

import java.net.URI;
import java.util.List;

/** Login and sign-up, followed by the email code step when needed. Closes with a live {@link Session}. */
final class AuthWindow extends BasicWindow {

    private final WindowBasedTextGUI gui;
    private final AuthService auth;
    private final ChatEvents events;
    private final TextBox email = new TextBox(new TerminalSize(32, 1));
    private final TextBox password = new TextBox(new TerminalSize(32, 1)).setMask('*');
    private final TextBox code = new TextBox(new TerminalSize(8, 1));
    private final Label status = new Label("");
    private final Panel codeStep = new Panel(new GridLayout(2));
    private boolean busy;
    private Session session;

    AuthWindow(WindowBasedTextGUI gui, AuthService auth, URI server, ChatEvents events) {
        super("terminal-send");
        this.gui = gui;
        this.auth = auth;
        this.events = events;
        setHints(List.of(Window.Hint.CENTERED));

        Panel form = new Panel(new GridLayout(2));
        form.addComponent(new Label("Email"));
        form.addComponent(email);
        form.addComponent(new Label("Senha"));
        form.addComponent(password);

        codeStep.addComponent(new Label("Código"));
        codeStep.addComponent(code);
        codeStep.setVisible(false);

        Panel buttons = new Panel(new LinearLayout(Direction.HORIZONTAL));
        buttons.addComponent(new Button("Entrar", this::onLogin));
        buttons.addComponent(new Button("Criar conta", this::onRegister));
        buttons.addComponent(new Button("Verificar", this::onVerify));
        buttons.addComponent(new Button("Reenviar código", this::onResend));
        buttons.addComponent(new Button("Sair", this::close));

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL));
        root.addComponent(new Label("Mensagens cifradas de ponta a ponta, histórico só nesta máquina."));
        root.addComponent(new Label("Servidor: " + server));
        root.addComponent(new EmptySpace());
        root.addComponent(form);
        root.addComponent(codeStep);
        root.addComponent(new EmptySpace());
        root.addComponent(buttons);
        root.addComponent(status);
        setComponent(root);
        setFocusedInteractable(email);
    }

    /** Null when the user chose to quit. */
    Session session() {
        return session;
    }

    private void onLogin() {
        submit("Entrando…", () -> auth.login(email.getText(), password.getText(), events), e -> {
            if (e instanceof ApiError api && api.is(ErrorCode.EMAIL_NOT_VERIFIED)) {
                showCodeStep("Confirme seu email: digite o código que enviamos (ou peça outro).");
                return true;
            }
            return false;
        });
    }

    private void onRegister() {
        if (busy) {
            return;
        }
        busy = true;
        status.setText("Criando conta…");
        Async.run(gui, () -> auth.register(email.getText(), password.getText()), registered -> {
            busy = false;
            showCodeStep("Conta " + registered.handle() + " criada. Enviamos um código de 6 dígitos para "
                    + email.getText().strip() + ".");
        }, this::showError);
    }

    private void onVerify() {
        if (!codeStep.isVisible()) {
            status.setText("Use \"Criar conta\" ou \"Entrar\" primeiro.");
            return;
        }
        submit("Verificando…", () -> auth.verify(email.getText(), password.getText(), code.getText(), events),
                e -> false);
    }

    private void onResend() {
        if (busy) {
            return;
        }
        busy = true;
        Async.run(gui, () -> {
            auth.resendCode(email.getText());
            return true;
        }, ok -> {
            busy = false;
            showCodeStep("Novo código enviado (se a conta existir e ainda não foi verificada).");
        }, this::showError);
    }

    private void submit(String progress, java.util.concurrent.Callable<Session> work,
                        java.util.function.Predicate<Exception> handled) {
        if (busy) {
            return;
        }
        busy = true;
        status.setText(progress);
        Async.run(gui, work, opened -> {
            session = opened;
            close();
        }, e -> {
            busy = false;
            if (!handled.test(e)) {
                showError(e);
            }
        });
    }

    private void showCodeStep(String message) {
        codeStep.setVisible(true);
        status.setText(message);
        setFocusedInteractable(code);
    }

    private void showError(Exception e) {
        busy = false;
        status.setText(switch (e) {
            case ApiError api when api.is(ErrorCode.INVALID_CREDENTIALS) -> "Email ou senha inválidos.";
            case ApiError api when api.is(ErrorCode.EMAIL_ALREADY_REGISTERED) -> "Esse email já tem conta. Use Entrar.";
            case ApiError api when api.is(ErrorCode.INVALID_VERIFICATION_CODE) -> "Código incorreto.";
            case ApiError api when api.is(ErrorCode.VERIFICATION_CODE_EXPIRED) -> "Código expirado. Peça outro.";
            case ApiError api when api.is(ErrorCode.TOO_MANY_ATTEMPTS) -> "Muitas tentativas. Aguarde ou peça outro código.";
            case ApiError api when api.is(ErrorCode.RATE_LIMITED) -> "Aguarde um minuto antes de pedir outro código.";
            case ApiError api -> api.getMessage();
            case UserFacingException user -> user.getMessage();
            default -> "Erro: " + e.getMessage();
        });
    }
}
