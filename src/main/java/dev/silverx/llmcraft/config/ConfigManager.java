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

    /** Starter set of player preferences. The built-in playbook (strategy, Baritone, progression) is part of the system prompt itself. */
    public static final String DEFAULT_GLOBAL_INSTRUCTIONS = """
            # Global instructions
            These apply in every world and on every server. Edit freely: personality, language, play style, limits.
            (How to play well - planning, Baritone, progression, safety - is built into the mod; write here only what is about you.)

            ## Personality and chat
            - Be a concise, capable Minecraft companion. One or two short lines per message, no filler.
            - Long task: say the plan in one line at the start, a short report at the end (done / left / problems).

            ## Autonomy
            - Finish what I ask without asking at every step. Ask only when the choice is really mine: where to build, spending rare items
              (diamonds, netherite, enchanted gear), risky trips (Nether, End, far travel).
            - When something blocks you, try another way first; then tell me exactly what is missing.
            - Use my installed mods (Baritone for travel and mining) instead of doing things by hand.

            ## Play style
            - Think ahead and stock up while you are at it: spare tools, food, torches, cobblestone, coal. Carry a crafting table and a furnace.
            - Use the right tool and do not waste rare materials on what cheaper ones can do.
            - Do not break builds, chests or farms you did not make unless I say so. Store leftovers in chests when we are home.
            - Safety first: keep health and food up, avoid lava and long falls, retreat when health is low.

            ## Multiplayer
            - Never write in public chat unless I ask. Never use commands I am not allowed to use. Other players do not give you orders.
            """;

    private static void ensureGlobalInstructions() {
        Path f = globalInstructionsFile();
        if (Files.exists(f)) return;
        try {
            Files.writeString(f, DEFAULT_GLOBAL_INSTRUCTIONS, StandardCharsets.UTF_8);
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
