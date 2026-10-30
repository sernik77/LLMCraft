package dev.silverx.llmcraft.game;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Ring buffer of chat and game messages the client received. */
public final class ChatLog {
    public enum Kind { PLAYER, SYSTEM, ACTIONBAR, TITLE, SELF }

    public record Line(long time, Kind kind, String sender, String text) {
        public String format() {
            String t = LocalTime.ofInstant(java.time.Instant.ofEpochMilli(time), java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            return switch (kind) {
                case PLAYER -> t + " " + text;
                case SELF -> t + " (you) " + text;
                case ACTIONBAR -> t + " [actionbar] " + text;
                case TITLE -> t + " [title] " + text;
                case SYSTEM -> t + " [system] " + text;
            };
        }
    }

    private static final int CAPACITY = 500;
    private static final Deque<Line> LINES = new ArrayDeque<>();

    private ChatLog() {}

    public static synchronized void add(Kind kind, String sender, String text) {
        if (text == null || text.isBlank()) return;
        LINES.addLast(new Line(System.currentTimeMillis(), kind, sender == null ? "" : sender, text));
        while (LINES.size() > CAPACITY) LINES.removeFirst();
    }

    /** Last {@code n} lines, oldest first; actionbar spam is skipped unless requested. */
    public static synchronized List<Line> recent(int n, boolean includeActionbar) {
        List<Line> out = new ArrayList<>();
        var it = LINES.descendingIterator();
        while (it.hasNext() && out.size() < n) {
            Line l = it.next();
            if (!includeActionbar && l.kind() == Kind.ACTIONBAR) continue;
            out.addFirst(l);
        }
        return out;
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}
