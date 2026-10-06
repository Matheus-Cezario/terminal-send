package dev.terminalsend.client;

import dev.terminalsend.client.ui.TerminalUi;

import java.io.IOException;
import java.nio.file.Path;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        String server = null;
        boolean insecure = false;
        Path home = ClientConfig.defaultHome();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server" -> server = requireValue(args, ++i, "--server");
                case "--home" -> home = Path.of(requireValue(args, ++i, "--home"));
                case "--insecure" -> insecure = true;
                case "-h", "--help" -> {
                    System.out.println("usage: terminal-send [--server <url>] [--home <dir>] [--insecure]");
                    return;
                }
                default -> {
                    System.err.println("unknown option: " + args[i]);
                    System.exit(2);
                }
            }
        }
        ClientConfig config;
        try {
            config = ClientConfig.load(home, server, insecure);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        new TerminalUi(config).run();
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            System.err.println(flag + " requires a value");
            System.exit(2);
        }
        return args[index];
    }
}
