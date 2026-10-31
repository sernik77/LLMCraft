package dev.silverx.llmcraft.config;

import dev.silverx.llmcraft.llm.ProviderProfile;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Global configuration, stored at config/llmcraft/config.json. Every field is user-editable. */
public final class LlmConfig {
    public boolean enabled = true;

    // ---- providers ----
    public String activeProfile = "ollama";
    public List<ProviderProfile> profiles = new ArrayList<>();

    // ---- permissions ----
    public Map<Category, PermissionMode> singleplayerPermissions = new EnumMap<>(Category.class);
    public Map<Category, PermissionMode> multiplayerPermissions = new EnumMap<>(Category.class);
    /** Server commands matching any of these (regex, without leading '/') are always refused. */
    public List<String> commandDenyPatterns = new ArrayList<>(List.of(
            "^(stop|op|deop|ban|ban-ip|pardon|pardon-ip|kick|whitelist|reload|save-off|transfer|debug|jfr|perf)\\b.*",
            "^(execute\\b.*\\b)?kill @[ae].*"));
    /** Server commands matching these run without asking even when COMMANDS is ASK. */
    public List<String> commandAutoAllowPatterns = new ArrayList<>(List.of("^(help|list|seed|time query .*)$"));
    /**
     * Chat prefixes that other client mods intercept, mapped to the mod id(s) (comma separated) that handle them.
     * A prefix is only usable while one of those mods is loaded, so nothing leaks to public chat.
     */
    public Map<String, String> modChatPrefixes = new LinkedHashMap<>(Map.of(".", "meteor-client", "#", "baritone,baritone-meteor"));
    /** Seconds an ASK approval waits before it is treated as denied. */
    public int approvalTimeoutSeconds = 60;

    // ---- agent ----
    public Agent agent = new Agent();
    public Context context = new Context();
    public Chat chat = new Chat();
    public Control control = new Control();
    public Triggers triggers = new Triggers();

    public static final class Agent {
        /** Max model round-trips (tool steps) per request. */
        public int maxSteps = 16;
        /** Max tool calls executed per single model reply. */
        public int maxToolCallsPerStep = 8;
        /** History messages (user+assistant turns) kept per world before compaction. */
        public int maxHistoryMessages = 40;
        /** Summarize old history with the model instead of dropping it. */
        public boolean summarizeHistory = true;
        /** Store short summaries of tool actions inside the history. */
        public boolean keepToolSummariesInHistory = true;
        /** Max characters of a single tool result fed back to the model. */
        public int maxToolResultChars = 4000;
        /** Overall timeout for one request (all steps), seconds. */
        public int requestTimeoutSeconds = 600;
        /**
         * "fresh": every step sends the same system prompt + history (cached by the server) plus ONE rebuilt message
         * with the request, notes, a compact log of what was done and the latest results - the context never fills up.
         * "append": the classic growing chat of tool calls and results.
         */
        public String contextMode = "fresh";
        /** fresh mode: how many one-line entries of earlier steps are shown. */
        public int stepLogEntries = 30;
        /** Include the built-in playbook (working method, Baritone, progression, portal, safety) in the system prompt. */
        public boolean includePlaybook = true;
        /** Extra text appended to the built-in system prompt (the global instructions file is separate). */
        public String extraSystemPrompt = "";
        /** Answer in this language (empty = same as the player). */
        public String language = "";
    }

    public static final class Context {
        public boolean includeGameState = true;
        public boolean includeRecentChat = true;
        public int recentChatLines = 20;
        public boolean includeNearbyEntities = true;
        public int entityRadius = 24;
        public int maxEntities = 12;
        public boolean includeInventorySummary = true;
        public boolean includePlayerList = true;
        public boolean includeScreenInfo = true;
        public boolean includeMemory = true;
        public boolean includeGlobalMemory = true;
        /** Max radius for block scans requested by the AI. */
        public int maxScanRadius = 32;
        public int clientLogMaxLines = 200;
    }

    public static final class Chat {
        /** Typing this prefix in chat sends the rest to the AI instead of the server (e.g. "@ai hello"). Empty = disabled. */
        public String promptPrefix = "@ai";
        /** Label shown before AI replies in your chat (local only). */
        public String displayName = "AI";
        public boolean showToolCalls = true;
        public boolean showThinking = false;
        public boolean showTokenUsage = false;
        /** Minimum milliseconds between two public chat messages sent by the AI. */
        public int minSendIntervalMs = 1500;
        /** Max public chat messages per request (anti-spam). */
        public int maxMessagesPerRequest = 5;
        /** Max length of one public message; longer text is split. Vanilla limit is 256. */
        public int maxMessageLength = 256;
        /** Optional prefix added to every public message the AI sends (e.g. "[bot] "). */
        public String outgoingPrefix = "";
    }

    public static final class Control {
        /** Max degrees the camera turns per tick when the AI looks somewhere (0 = instant). */
        public float lookSpeedDegPerTick = 25f;
        /** Hard cap for a single movement/hold action, in ticks (20 ticks = 1 s). */
        public int maxActionTicks = 20 * 60;
        /** Hard cap for one long-running AI task (mining, smelting, Baritone travel), in seconds. */
        public int maxTaskSeconds = 600;
        /** Stop all AI control when you take damage. */
        public boolean stopOnDamage = false;
        /** Stop all AI control when you press a movement key yourself. */
        public boolean stopOnPlayerInput = true;
        /** While the AI acts: run from a hissing creeper, and run when health is at/below fleeHealth with monsters close. */
        public boolean autoFlee = true;
        public float fleeHealth = 6;
    }

    public static final class Triggers {
        /** Master switch for everything below. Triggers run without you typing anything. */
        public boolean enabled = false;
        /** Chat lines (from others) matching any regex are sent to the AI. Use (?i) for case-insensitive. */
        public List<String> chatPatterns = new ArrayList<>(List.of("(?i)\\bAI\\b[,:]"));
        public boolean onDeath = false;
        public boolean onJoin = false;
        /** Run the scheduled prompt every N seconds (0 = off). */
        public int intervalSeconds = 0;
        public String intervalPrompt = "Check the situation and act according to your instructions if needed.";
        /** Min seconds between two automatic triggers. */
        public int cooldownSeconds = 10;
    }

    public ProviderProfile activeProfile() {
        return profile(activeProfile);
    }

    public ProviderProfile profile(String name) {
        for (ProviderProfile p : profiles) {
            if (p.name.equalsIgnoreCase(name)) return p;
        }
        return profiles.isEmpty() ? null : profiles.getFirst();
    }

    /** Fills in anything missing after loading an older/partial file. */
    public void applyDefaults() {
        if (profiles == null) profiles = new ArrayList<>();
        if (profiles.isEmpty()) profiles.addAll(defaultProfiles());
        if (singleplayerPermissions == null) singleplayerPermissions = new EnumMap<>(Category.class);
        if (multiplayerPermissions == null) multiplayerPermissions = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            singleplayerPermissions.putIfAbsent(c, defaultMode(c, false));
            multiplayerPermissions.putIfAbsent(c, defaultMode(c, true));
        }
        if (agent == null) agent = new Agent();
        if (context == null) context = new Context();
        if (chat == null) chat = new Chat();
        if (control == null) control = new Control();
        if (triggers == null) triggers = new Triggers();
        if (commandDenyPatterns == null) commandDenyPatterns = new ArrayList<>();
        if (commandAutoAllowPatterns == null) commandAutoAllowPatterns = new ArrayList<>();
        if (modChatPrefixes == null) modChatPrefixes = new LinkedHashMap<>();
        for (ProviderProfile p : profiles) {
            if (p.headers == null) p.headers = new LinkedHashMap<>();
            if (p.extraBody == null) p.extraBody = new com.google.gson.JsonObject();
            if (p.toolMode == null) p.toolMode = ProviderProfile.ToolMode.NATIVE;
            if (p.type == null) p.type = ProviderProfile.Type.OPENAI;
        }
    }

    static PermissionMode defaultMode(Category c, boolean multiplayer) {
        return switch (c) {
            case CHAT_READ, WORLD_READ, PLAYER_READ, SCREEN, MOVEMENT, MODS, CLIENT_LOG, VISION, MEMORY -> PermissionMode.ALLOW;
            case CHAT_SEND, INVENTORY, INTERACTION, BARITONE -> multiplayer ? PermissionMode.ASK : PermissionMode.ALLOW;
            case COMMANDS, CLIENT_COMMANDS, KEYBINDS, SCRIPTS -> PermissionMode.ASK;
        };
    }

    static List<ProviderProfile> defaultProfiles() {
        List<ProviderProfile> list = new ArrayList<>();
        ProviderProfile ollama = ProviderProfile.of("ollama", ProviderProfile.Type.OLLAMA, "http://localhost:11434", "", "qwen3.5:4b");
        ollama.vision = true;
        // ~45 tool schemas alone take ~4k tokens; 16k leaves room for history and tool results.
        ollama.contextTokens = 16384;
        ollama.extraBody.addProperty("keep_alive", "30m");
        list.add(ollama);

        ProviderProfile openai = ProviderProfile.of("openai", ProviderProfile.Type.OPENAI, "https://api.openai.com/v1", "env:OPENAI_API_KEY", "gpt-5-mini");
        openai.vision = true;
        openai.contextTokens = 128000;
        list.add(openai);

        ProviderProfile claude = ProviderProfile.of("claude", ProviderProfile.Type.ANTHROPIC, "https://api.anthropic.com", "env:ANTHROPIC_API_KEY", "claude-sonnet-5-5");
        claude.vision = true;
        claude.contextTokens = 200000;
        claude.maxTokens = 4096;
        list.add(claude);

        ProviderProfile openrouter = ProviderProfile.of("openrouter", ProviderProfile.Type.OPENAI, "https://openrouter.ai/api/v1", "env:OPENROUTER_API_KEY", "openai/gpt-5-mini");
        openrouter.contextTokens = 128000;
        openrouter.headers.put("X-Title", "LLMCraft");
        list.add(openrouter);

        ProviderProfile gemini = ProviderProfile.of("gemini", ProviderProfile.Type.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai", "env:GEMINI_API_KEY", "gemini-2.5-flash");
        gemini.vision = true;
        gemini.contextTokens = 1000000;
        list.add(gemini);

        ProviderProfile lmstudio = ProviderProfile.of("lmstudio", ProviderProfile.Type.OPENAI, "http://localhost:1234/v1", "", "local-model");
        lmstudio.contextTokens = 16384;
        list.add(lmstudio);
        return list;
    }
}
