package dev.terminalsend.client.ui;

import com.googlecode.lanterna.gui2.WindowBasedTextGUI;

import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Runs blocking work (network, key derivation) off the GUI thread and hands the result back to it. */
final class Async {

    private Async() {
    }

    static <T> void run(WindowBasedTextGUI gui, Callable<T> work, Consumer<T> onSuccess, Consumer<Exception> onError) {
        Thread.ofVirtual().start(() -> {
            try {
                T result = work.call();
                gui.getGUIThread().invokeLater(() -> onSuccess.accept(result));
            } catch (Exception e) {
                gui.getGUIThread().invokeLater(() -> onError.accept(e));
            }
        });
    }
}
