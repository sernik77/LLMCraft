package dev.silverx.llmcraft.data;

/** One remembered fact. */
public final class MemoryEntry {
    public int id;
    public String text;
    public long createdAt;
    public String tag = "";

    public MemoryEntry() {}

    public MemoryEntry(int id, String text, String tag) {
        this.id = id;
        this.text = text;
        this.tag = tag == null ? "" : tag;
        this.createdAt = System.currentTimeMillis();
    }
}
