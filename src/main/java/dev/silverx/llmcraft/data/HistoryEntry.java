package dev.silverx.llmcraft.data;

/** A persisted conversation turn. Only user/assistant text (tool chatter is summarized into assistant text). */
public final class HistoryEntry {
    public String role;
    public String text;
    public long time;

    public HistoryEntry() {}

    public HistoryEntry(String role, String text) {
        this.role = role;
        this.text = text;
        this.time = System.currentTimeMillis();
    }
}
