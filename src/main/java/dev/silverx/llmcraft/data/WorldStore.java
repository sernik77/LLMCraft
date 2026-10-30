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

/**
 * Everything the AI knows about one world or server:
 * instructions.md, memory.json, history.json (+ summary.md), settings.json.
 */
public final class WorldStore {
    public final String key;
    public final String displayName;
    public final boolean multiplayer;
    public final Path dir;
    public final MemoryStore memory;
    private final List<HistoryEntry> history = new ArrayList<>();
    private String summary = "";
    private WorldSettings settings = new WorldSettings();

    public WorldStore(String key, String displayName, boolean multiplayer) {
        this.key = key;
        this.displayName = displayName;
        this.multiplayer = multiplayer;
        this.dir = ConfigManager.dir().resolve("worlds").resolve(key);
        this.memory = new MemoryStore(dir.resolve("memory.json"));
        load();
    }

    private void load() {
        try {
            Files.createDirectories(dir);
            Path s = dir.resolve("settings.json");
            if (Files.exists(s)) {
                WorldSettings loaded = ConfigManager.GSON.fromJson(Files.readString(s, StandardCharsets.UTF_8), WorldSettings.class);
                if (loaded != null) settings = loaded;
            }
            if (settings.permissions == null) settings.permissions = new java.util.EnumMap<>(dev.silverx.llmcraft.config.Category.class);
            if (settings.label == null || settings.label.isEmpty()) settings.label = displayName;
            saveSettings();

            Path h = dir.resolve("history.json");
            if (Files.exists(h)) {
                List<HistoryEntry> list = ConfigManager.GSON.fromJson(Files.readString(h, StandardCharsets.UTF_8),
                        new TypeToken<List<HistoryEntry>>() {}.getType());
                if (list != null) history.addAll(list);
            }
            Path sum = dir.resolve("summary.md");
            if (Files.exists(sum)) summary = Files.readString(sum, StandardCharsets.UTF_8).trim();

            Path instr = instructionsFile();
            if (!Files.exists(instr)) {
                Files.writeString(instr, "# Instructions for " + displayName + "\n"
                        + "Only used in this " + (multiplayer ? "server" : "world") + ". Rules, goals, context, server etiquette...\n",
                        StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LlmCraft.LOGGER.error("Could not load world data {}", dir, e);
        }
    }

    public Path instructionsFile() {
        return dir.resolve("instructions.md");
    }

    public String instructions() {
        try {
            return Files.exists(instructionsFile()) ? Files.readString(instructionsFile(), StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    public synchronized void appendInstructions(String text) {
        try {
            Files.writeString(instructionsFile(), instructions() + "\n" + text.trim() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            LlmCraft.LOGGER.error("Could not write instructions", e);
        }
    }

    public synchronized WorldSettings settings() {
        return settings;
    }

    public synchronized void saveSettings() {
        write(dir.resolve("settings.json"), ConfigManager.GSON.toJson(settings));
    }

    public synchronized List<HistoryEntry> history() {
        return new ArrayList<>(history);
    }

    public synchronized void addHistory(HistoryEntry e) {
        history.add(e);
        saveHistory();
    }

    /** Replace the oldest {@code count} entries with a new running summary. */
    public synchronized void compact(int count, String newSummary) {
        for (int i = 0; i < count && !history.isEmpty(); i++) history.removeFirst();
        if (newSummary != null) {
            summary = newSummary.trim();
            write(dir.resolve("summary.md"), summary);
        }
        saveHistory();
    }

    public synchronized void clearHistory() {
        history.clear();
        summary = "";
        write(dir.resolve("summary.md"), "");
        saveHistory();
    }

    public synchronized String summary() {
        return summary;
    }

    private void saveHistory() {
        write(dir.resolve("history.json"), ConfigManager.GSON.toJson(history));
    }

    private static void write(Path p, String content) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LlmCraft.LOGGER.error("Could not write {}", p, e);
        }
    }

    /** Lowercase, filesystem-safe key. */
    public static String sanitize(String raw) {
        String s = raw == null ? "unknown" : raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
        s = s.replaceAll("^[._]+", "");
        if (s.isEmpty()) s = "unknown";
        return s.length() > 80 ? s.substring(0, 80) : s;
    }
}
