package dev.silverx.llmcraft.compat;

import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.config.ConfigManager;
import dev.silverx.llmcraft.config.LlmConfig;
import dev.silverx.llmcraft.config.PermissionMode;
import dev.silverx.llmcraft.data.Stores;
import dev.silverx.llmcraft.llm.ProviderProfile;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Full settings screen (Mod Menu → LLMCraft). Only loaded when Cloth Config is installed. */
public final class ClothConfigScreen {
    private ClothConfigScreen() {}

    private static Component t(String s) {
        return Component.literal(s);
    }

    public static Screen create(Screen parent) {
        LlmConfig c = ConfigManager.get();
        LlmConfig d = new LlmConfig();
        d.applyDefaults();
        ConfigBuilder b = ConfigBuilder.create().setParentScreen(parent).setTitle(t("LLMCraft"))
                .setSavingRunnable(() -> {
                    c.applyDefaults();
                    ConfigManager.save();
                    Stores.reset();
                });
        ConfigEntryBuilder e = b.entryBuilder();

        // ---- General ----
        ConfigCategory general = b.getOrCreateCategory(t("General"));
        general.addEntry(e.startBooleanToggle(t("Enabled"), c.enabled).setDefaultValue(true).setSaveConsumer(v -> c.enabled = v).build());
        List<String> names = new ArrayList<>();
        c.profiles.forEach(p -> names.add(p.name));
        general.addEntry(e.startStringDropdownMenu(t("Active provider profile"), c.activeProfile).setSelections(names)
                .setTooltip(t("Profiles are edited in the 'Providers' tab. Add new ones in config.json or with /llm config set."))
                .setSaveConsumer(v -> c.activeProfile = v).build());
        general.addEntry(e.startStrField(t("Chat prompt prefix"), c.chat.promptPrefix).setDefaultValue(d.chat.promptPrefix)
                .setTooltip(t("Typing this in chat sends the message to the AI instead of the server. Empty = off.")).setSaveConsumer(v -> c.chat.promptPrefix = v).build());
        general.addEntry(e.startStrField(t("AI display name"), c.chat.displayName).setDefaultValue(d.chat.displayName).setSaveConsumer(v -> c.chat.displayName = v).build());
        general.addEntry(e.startStrField(t("Answer language (empty = auto)"), c.agent.language).setDefaultValue("").setSaveConsumer(v -> c.agent.language = v).build());
        general.addEntry(e.startStrField(t("Extra system prompt"), c.agent.extraSystemPrompt).setDefaultValue("")
                .setTooltip(t("Short extra rules. Longer instructions: config/llmcraft/global_instructions.md and per-world instructions.md")).setSaveConsumer(v -> c.agent.extraSystemPrompt = v).build());
        general.addEntry(e.startBooleanToggle(t("Show tool calls"), c.chat.showToolCalls).setDefaultValue(true).setSaveConsumer(v -> c.chat.showToolCalls = v).build());
        general.addEntry(e.startBooleanToggle(t("Show model thinking"), c.chat.showThinking).setDefaultValue(false).setSaveConsumer(v -> c.chat.showThinking = v).build());
        general.addEntry(e.startBooleanToggle(t("Show token usage"), c.chat.showTokenUsage).setDefaultValue(false).setSaveConsumer(v -> c.chat.showTokenUsage = v).build());

        // ---- Providers ----
        ConfigCategory providers = b.getOrCreateCategory(t("Providers"));
        providers.addEntry(e.startTextDescription(t("OPENAI = any OpenAI-compatible API (OpenAI, OpenRouter, Groq, LM Studio, llama.cpp, vLLM, Gemini, DeepSeek, Ollama /v1). "
                + "API keys can be written as env:VARIABLE to read them from the environment.")).build());
        for (ProviderProfile p : c.profiles) {
            SubCategoryBuilder sub = e.startSubCategory(t(p.name + (p.name.equalsIgnoreCase(c.activeProfile) ? "  (active)" : "")));
            sub.add(e.startEnumSelector(t("Type"), ProviderProfile.Type.class, p.type).setSaveConsumer(v -> p.type = v).build());
            sub.add(e.startStrField(t("Base URL"), p.baseUrl).setSaveConsumer(v -> p.baseUrl = v).build());
            sub.add(e.startStrField(t("API key (or env:VAR)"), p.apiKey).setSaveConsumer(v -> p.apiKey = v).build());
            sub.add(e.startStrField(t("Model"), p.model).setSaveConsumer(v -> p.model = v).build());
            sub.add(e.startDoubleField(t("Temperature"), p.temperature).setMin(0).setMax(2).setSaveConsumer(v -> p.temperature = v).build());
            sub.add(e.startIntField(t("Max output tokens (0 = default)"), p.maxTokens).setMin(0).setSaveConsumer(v -> p.maxTokens = v).build());
            sub.add(e.startIntField(t("Context window (tokens)"), p.contextTokens).setMin(1024).setSaveConsumer(v -> p.contextTokens = v).build());
            sub.add(e.startEnumSelector(t("Tool calling"), ProviderProfile.ToolMode.class, p.toolMode)
                    .setTooltip(t("NATIVE = function calling; JSON = for models without tool support; NONE = chat only")).setSaveConsumer(v -> p.toolMode = v).build());
            sub.add(e.startBooleanToggle(t("Vision (accepts images)"), p.vision).setSaveConsumer(v -> p.vision = v).build());
            sub.add(e.startIntField(t("Timeout (s)"), p.timeoutSeconds).setMin(5).setSaveConsumer(v -> p.timeoutSeconds = v).build());
            providers.addEntry(sub.build());
        }

        // ---- Permissions ----
        permissions(b, e, "Permissions: singleplayer", c.singleplayerPermissions, false);
        permissions(b, e, "Permissions: multiplayer", c.multiplayerPermissions, true);

        // ---- Safety ----
        ConfigCategory safety = b.getOrCreateCategory(t("Chat & safety"));
        safety.addEntry(e.startIntField(t("Min ms between public messages"), c.chat.minSendIntervalMs).setMin(0).setDefaultValue(d.chat.minSendIntervalMs).setSaveConsumer(v -> c.chat.minSendIntervalMs = v).build());
        safety.addEntry(e.startIntField(t("Max public messages per request"), c.chat.maxMessagesPerRequest).setMin(0).setDefaultValue(d.chat.maxMessagesPerRequest).setSaveConsumer(v -> c.chat.maxMessagesPerRequest = v).build());
        safety.addEntry(e.startIntField(t("Max message length"), c.chat.maxMessageLength).setMin(16).setMax(256).setDefaultValue(256).setSaveConsumer(v -> c.chat.maxMessageLength = v).build());
        safety.addEntry(e.startStrField(t("Prefix for AI's public messages"), c.chat.outgoingPrefix).setDefaultValue("").setSaveConsumer(v -> c.chat.outgoingPrefix = v).build());
        safety.addEntry(e.startStrList(t("Blocked commands (regex)"), c.commandDenyPatterns).setDefaultValue(d.commandDenyPatterns).setSaveConsumer(v -> c.commandDenyPatterns = new ArrayList<>(v)).build());
        safety.addEntry(e.startStrList(t("Commands allowed without asking (regex)"), c.commandAutoAllowPatterns).setDefaultValue(d.commandAutoAllowPatterns).setSaveConsumer(v -> c.commandAutoAllowPatterns = new ArrayList<>(v)).build());
        safety.addEntry(e.startIntField(t("Approval timeout (s)"), c.approvalTimeoutSeconds).setMin(5).setDefaultValue(60).setSaveConsumer(v -> c.approvalTimeoutSeconds = v).build());

        // ---- Agent / context ----
        ConfigCategory agent = b.getOrCreateCategory(t("Agent & context"));
        agent.addEntry(e.startIntField(t("Max steps per request"), c.agent.maxSteps).setMin(1).setDefaultValue(d.agent.maxSteps).setSaveConsumer(v -> c.agent.maxSteps = v).build());
        agent.addEntry(e.startIntField(t("Max tool calls per step"), c.agent.maxToolCallsPerStep).setMin(1).setDefaultValue(d.agent.maxToolCallsPerStep).setSaveConsumer(v -> c.agent.maxToolCallsPerStep = v).build());
        agent.addEntry(e.startIntField(t("Request timeout (s)"), c.agent.requestTimeoutSeconds).setMin(10).setDefaultValue(d.agent.requestTimeoutSeconds).setSaveConsumer(v -> c.agent.requestTimeoutSeconds = v).build());
        agent.addEntry(e.startIntField(t("History messages kept per world"), c.agent.maxHistoryMessages).setMin(4).setDefaultValue(d.agent.maxHistoryMessages).setSaveConsumer(v -> c.agent.maxHistoryMessages = v).build());
        agent.addEntry(e.startBooleanToggle(t("Summarize old history"), c.agent.summarizeHistory).setDefaultValue(true).setSaveConsumer(v -> c.agent.summarizeHistory = v).build());
        agent.addEntry(e.startBooleanToggle(t("Keep action summaries in history"), c.agent.keepToolSummariesInHistory).setDefaultValue(true).setSaveConsumer(v -> c.agent.keepToolSummariesInHistory = v).build());
        agent.addEntry(e.startIntField(t("Max chars per tool result"), c.agent.maxToolResultChars).setMin(200).setDefaultValue(d.agent.maxToolResultChars).setSaveConsumer(v -> c.agent.maxToolResultChars = v).build());
        agent.addEntry(e.startBooleanToggle(t("Context: game state"), c.context.includeGameState).setDefaultValue(true).setSaveConsumer(v -> c.context.includeGameState = v).build());
        agent.addEntry(e.startBooleanToggle(t("Context: recent chat"), c.context.includeRecentChat).setDefaultValue(true).setSaveConsumer(v -> c.context.includeRecentChat = v).build());
        agent.addEntry(e.startIntField(t("Context: chat lines"), c.context.recentChatLines).setMin(0).setDefaultValue(d.context.recentChatLines).setSaveConsumer(v -> c.context.recentChatLines = v).build());
        agent.addEntry(e.startBooleanToggle(t("Context: nearby entities"), c.context.includeNearbyEntities).setDefaultValue(true).setSaveConsumer(v -> c.context.includeNearbyEntities = v).build());
        agent.addEntry(e.startIntField(t("Context: entity radius"), c.context.entityRadius).setMin(1).setDefaultValue(d.context.entityRadius).setSaveConsumer(v -> c.context.entityRadius = v).build());
        agent.addEntry(e.startIntField(t("Context: max entities"), c.context.maxEntities).setMin(0).setDefaultValue(d.context.maxEntities).setSaveConsumer(v -> c.context.maxEntities = v).build());
        agent.addEntry(e.startBooleanToggle(t("Context: inventory summary"), c.context.includeInventorySummary).setDefaultValue(true).setSaveConsumer(v -> c.context.includeInventorySummary = v).build());
        agent.addEntry(e.startBooleanToggle(t("Context: player list"), c.context.includePlayerList).setDefaultValue(true).setSaveConsumer(v -> c.context.includePlayerList = v).build());
        agent.addEntry(e.startBooleanToggle(t("Context: open screen"), c.context.includeScreenInfo).setDefaultValue(true).setSaveConsumer(v -> c.context.includeScreenInfo = v).build());
        agent.addEntry(e.startBooleanToggle(t("Context: world memory"), c.context.includeMemory).setDefaultValue(true).setSaveConsumer(v -> c.context.includeMemory = v).build());
        agent.addEntry(e.startBooleanToggle(t("Context: global memory"), c.context.includeGlobalMemory).setDefaultValue(true).setSaveConsumer(v -> c.context.includeGlobalMemory = v).build());
        agent.addEntry(e.startIntField(t("Max block scan radius"), c.context.maxScanRadius).setMin(1).setMax(64).setDefaultValue(d.context.maxScanRadius).setSaveConsumer(v -> c.context.maxScanRadius = v).build());
        agent.addEntry(e.startIntField(t("Max client log lines"), c.context.clientLogMaxLines).setMin(10).setDefaultValue(d.context.clientLogMaxLines).setSaveConsumer(v -> c.context.clientLogMaxLines = v).build());

        // ---- Control ----
        ConfigCategory control = b.getOrCreateCategory(t("Player control"));
        control.addEntry(e.startFloatField(t("Camera speed (deg/tick, 0 = instant)"), c.control.lookSpeedDegPerTick).setMin(0).setDefaultValue(d.control.lookSpeedDegPerTick).setSaveConsumer(v -> c.control.lookSpeedDegPerTick = v).build());
        control.addEntry(e.startIntField(t("Max action length (ticks)"), c.control.maxActionTicks).setMin(20).setDefaultValue(d.control.maxActionTicks).setSaveConsumer(v -> c.control.maxActionTicks = v).build());
        control.addEntry(e.startBooleanToggle(t("Stop when I move"), c.control.stopOnPlayerInput).setDefaultValue(true).setSaveConsumer(v -> c.control.stopOnPlayerInput = v).build());
        control.addEntry(e.startBooleanToggle(t("Stop when I take damage"), c.control.stopOnDamage).setDefaultValue(false).setSaveConsumer(v -> c.control.stopOnDamage = v).build());

        // ---- Triggers ----
        ConfigCategory triggers = b.getOrCreateCategory(t("Triggers"));
        triggers.addEntry(e.startTextDescription(t("Triggers start the AI without you typing. Keep them off on public servers unless you know what you are doing.")).build());
        triggers.addEntry(e.startBooleanToggle(t("Enabled"), c.triggers.enabled).setDefaultValue(false).setSaveConsumer(v -> c.triggers.enabled = v).build());
        triggers.addEntry(e.startStrList(t("Chat patterns (regex)"), c.triggers.chatPatterns).setDefaultValue(d.triggers.chatPatterns).setSaveConsumer(v -> c.triggers.chatPatterns = new ArrayList<>(v)).build());
        triggers.addEntry(e.startBooleanToggle(t("On death"), c.triggers.onDeath).setDefaultValue(false).setSaveConsumer(v -> c.triggers.onDeath = v).build());
        triggers.addEntry(e.startBooleanToggle(t("On join"), c.triggers.onJoin).setDefaultValue(false).setSaveConsumer(v -> c.triggers.onJoin = v).build());
        triggers.addEntry(e.startIntField(t("Interval (s, 0 = off)"), c.triggers.intervalSeconds).setMin(0).setDefaultValue(0).setSaveConsumer(v -> c.triggers.intervalSeconds = v).build());
        triggers.addEntry(e.startStrField(t("Interval prompt"), c.triggers.intervalPrompt).setDefaultValue(d.triggers.intervalPrompt).setSaveConsumer(v -> c.triggers.intervalPrompt = v).build());
        triggers.addEntry(e.startIntField(t("Cooldown (s)"), c.triggers.cooldownSeconds).setMin(0).setDefaultValue(d.triggers.cooldownSeconds).setSaveConsumer(v -> c.triggers.cooldownSeconds = v).build());

        return b.build();
    }

    private static void permissions(ConfigBuilder b, ConfigEntryBuilder e, String title, Map<Category, PermissionMode> map, boolean multiplayer) {
        ConfigCategory cat = b.getOrCreateCategory(t(title));
        cat.addEntry(e.startTextDescription(t("OFF = the AI does not get the ability; ASK = you approve each use (chat buttons or the Approve/Deny keys); ALLOW = no questions. "
                + "Per world/server overrides: /llm perm <category> <mode> world")).build());
        for (Category c : Category.values()) {
            cat.addEntry(e.startEnumSelector(t(c.name().toLowerCase().replace('_', ' ')), PermissionMode.class, map.getOrDefault(c, PermissionMode.ASK))
                    .setTooltip(t(c.description))
                    .setSaveConsumer(v -> map.put(c, v)).build());
        }
    }
}
