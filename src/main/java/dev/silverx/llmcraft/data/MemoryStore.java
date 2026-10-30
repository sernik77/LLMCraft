package dev.silverx.llmcraft.data;

import com.google.gson.reflect.TypeToken;
import dev.silverx.llmcraft.LlmCraft;
import dev.silverx.llmcraft.config.ConfigManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A JSON list of facts (global or per world). Thread-safe. */
public final class MemoryStore {
    private final Path file;
    private final List<MemoryEntry> entries = new ArrayList<>();

    public MemoryStore(Path file) {
        this.file = file;
        load();
    }

    private synchronized void load() {
        entries.clear();
        try {
            if (Files.exists(file)) {
                List<MemoryEntry> list = ConfigManager.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        new TypeToken<List<MemoryEntry>>() {}.getType());
                if (list != null) entries.addAll(list);
            }
        } catch (Exception e) {
            LlmCraft.LOGGER.error("Could not read memory {}", file, e);
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, ConfigManager.GSON.toJson(entries), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LlmCraft.LOGGER.error("Could not save memory {}", file, e);
        }
    }

    public synchronized MemoryEntry add(String text, String tag) {
        int id = entries.stream().mapToInt(e -> e.id).max().orElse(0) + 1;
        MemoryEntry e = new MemoryEntry(id, text.trim(), tag);
        entries.add(e);
        save();
        return e;
    }

    public synchronized boolean remove(int id) {
        boolean removed = entries.removeIf(e -> e.id == id);
        if (removed) save();
        return removed;
    }

    public synchronized boolean update(int id, String text) {
        for (MemoryEntry e : entries) {
            if (e.id == id) {
                e.text = text.trim();
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized void clear() {
        entries.clear();
        save();
    }

    public synchronized List<MemoryEntry> all() {
        return new ArrayList<>(entries);
    }

    public synchronized String render() {
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry e : entries) {
            sb.append("- [#").append(e.id).append(e.tag.isEmpty() ? "" : " " + e.tag).append("] ").append(e.text).append('\n');
        }
        return sb.toString();
    }
}
