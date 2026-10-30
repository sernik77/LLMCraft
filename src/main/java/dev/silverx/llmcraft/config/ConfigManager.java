package dev.silverx.llmcraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.silverx.llmcraft.LlmCraft;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Loads and saves config/llmcraft/config.json plus the global instruction file. */
public final class ConfigManager {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    private static LlmConfig config = new LlmConfig();

    private ConfigManager() {}

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve(LlmCraft.MOD_ID);
    }

    public static Path configFile() {
        return dir().resolve("config.json");
    }

    public static Path globalInstructionsFile() {
        return dir().resolve("global_instructions.md");
    }

    public static LlmConfig get() {
        return config;
    }

    public static synchronized void load() {
        Path file = configFile();
        LlmConfig loaded = null;
        try {
            Files.createDirectories(dir());
            if (Files.exists(file)) {
                loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), LlmConfig.class);
            }
        } catch (Exception e) {
            LlmCraft.LOGGER.error("Could not read {}, keeping a backup and using defaults", file, e);
            try {
                Files.copy(file, file.resolveSibling("config.json.broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
        }
        if (loaded == null) loaded = new LlmConfig();
        loaded.applyDefaults();
        config = loaded;
        save();
        ensureGlobalInstructions();
    }

    public static synchronized void replace(LlmConfig updated) {
        config = updated;
        save();
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(dir());
            Path tmp = configFile().resolveSibling("config.json.tmp");
            Files.writeString(tmp, GSON.toJson(config), StandardCharsets.UTF_8);
            Files.move(tmp, configFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LlmCraft.LOGGER.error("Could not save config", e);
        }
    }

    private static void ensureGlobalInstructions() {
        Path f = globalInstructionsFile();
        if (Files.exists(f)) return;
        try {
            Files.writeString(f, """
                    # Global instructions
                    These instructions apply in every world and on every server.
                    Write anything here: personality, language, play style, rules, what to never do.

                    - Be concise in chat. You are a helpful Minecraft companion.
                    """, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LlmCraft.LOGGER.error("Could not create {}", f, e);
        }
    }

    public static String readGlobalInstructions() {
        try {
            Path f = globalInstructionsFile();
            return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }
}
