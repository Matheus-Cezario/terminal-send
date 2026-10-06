package dev.terminalsend.client.ui;

import dev.terminalsend.client.store.StoredMessage;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Plain-text rendering of the chat pane, kept free of Lanterna so it is easy to test. */
final class MessageLines {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private MessageLines() {
    }

    static String format(StoredMessage message, String peerName, ZoneId zone) {
        String time = TIME.format(message.sentAt().atZone(zone));
        if (message.direction() == StoredMessage.Direction.IN) {
            return "[" + time + "] " + peerName + ": " + message.body();
        }
        return "[" + time + "] você: " + message.body() + "  " + mark(message.state());
    }

    /** … queued locally · ✓ stored by the server · ✓✓ delivered · ✗ failed. */
    static String mark(StoredMessage.State state) {
        return switch (state) {
            case QUEUED -> "…";
            case SENT -> "✓";
            case DELIVERED -> "✓✓";
            case FAILED -> "✗";
            case RECEIVED -> "";
        };
    }

    /** Hard-wraps to {@code width}, indenting continuation lines so messages stay visually grouped. */
    static List<String> wrap(String line, int width) {
        List<String> out = new ArrayList<>();
        if (width < 10 || line.length() <= width) {
            out.add(line);
            return out;
        }
        String rest = line;
        boolean first = true;
        while (!rest.isEmpty()) {
            int room = first ? width : width - 4;
            String prefix = first ? "" : "    ";
            if (rest.length() <= room) {
                out.add(prefix + rest);
                break;
            }
            int cut = rest.lastIndexOf(' ', room);
            if (cut <= room / 2) {
                cut = room;
            }
            out.add(prefix + rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut).stripLeading();
            first = false;
        }
        return out;
    }
}
