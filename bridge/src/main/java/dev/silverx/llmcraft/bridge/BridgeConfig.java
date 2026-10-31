package dev.silverx.llmcraft.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.silverx.llmcraft.config.ConfigManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** config/llmcraft/bridge.json */
public final class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static BridgeConfig instance = new BridgeConfig();

    public Meteor meteor = new Meteor();
    public JsMacros jsmacros = new JsMacros();

    public static final class Meteor {
        /** Meteor commands the AI may never run (without the prefix). */
        public List<String> blockedCommands = new ArrayList<>(List.of("reset", "swarm"));
        /** Meteor modules the AI may never toggle or configure (module names, e.g. "kill-aura"). */
        public List<String> blockedModules = new ArrayList<>();
    }

    public static final class JsMacros {
        /** Lets the AI run code it writes itself (full access to the game and your files). Off unless you turn it on. */
        public boolean allowInlineCode = false;
        /** How long the jsmacros tool waits for a script by default. */
        public int runTimeoutSeconds = 60;
        /** Longest LLM.askAndWait() a script may block for. */
        public int askTimeoutSeconds = 300;
        /** Fire the LLMRequest/LLMReply/LLMTool/LLMDone/LLMStop events for macros. */
        public boolean fireEvents = true;
    }

    public static BridgeConfig get() {
        return instance;
    }

    public static Path file() {
        return ConfigManager.dir().resolve("bridge.json");
    }

    public static synchronized void load() {
        Path f = file();
        try {
            if (Files.isRegularFile(f)) {
                BridgeConfig c = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), BridgeConfig.class);
                if (c != null) instance = c;
            }
        } catch (Exception e) {
            Bridge.LOGGER.warn("Could not read {}, using defaults", f, e);
        }
        if (instance.meteor == null) instance.meteor = new Meteor();
        if (instance.jsmacros == null) instance.jsmacros = new JsMacros();
        if (instance.meteor.blockedCommands == null) instance.meteor.blockedCommands = new ArrayList<>();
        if (instance.meteor.blockedModules == null) instance.meteor.blockedModules = new ArrayList<>();
        save();
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Bridge.LOGGER.warn("Could not save {}", file(), e);
        }
    }
}
