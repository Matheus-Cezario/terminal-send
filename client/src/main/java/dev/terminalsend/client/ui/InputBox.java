package dev.terminalsend.client.ui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.util.function.Consumer;

/** Single-line box that submits on Enter and clears itself. */
final class InputBox extends TextBox {

    private final Consumer<String> onSubmit;

    InputBox(Consumer<String> onSubmit) {
        super(new TerminalSize(40, 1));
        this.onSubmit = onSubmit;
    }

    @Override
    public synchronized Result handleKeyStroke(KeyStroke keyStroke) {
        if (keyStroke.getKeyType() == KeyType.Enter) {
            String text = getText();
            setText("");
            onSubmit.accept(text);
            return Result.HANDLED;
        }
        return super.handleKeyStroke(keyStroke);
    }
}
