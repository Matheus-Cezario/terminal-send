package dev.terminalsend.client;

import dev.terminalsend.client.ui.TerminalUi;

import java.io.IOException;
import java.nio.file.Path;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        String server = null;
        Path home = ClientConfig.defaultHome();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server" -> server = requireValue(args, ++i, "--server");
                case "--home" -> home = Path.of(requireValue(args, ++i, "--home"));
                case "-h", "--help" -> {
                    System.out.println("usage: terminal-send [--server <url>] [--home <dir>]");
                    return;
                }
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    System.exit(2);
                }
            }
        }
        new TerminalUi(ClientConfig.load(home, server)).run();
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            System.err.println(flag + " requires a value");
            System.exit(2);
        }
        return args[index];
    }
}
