package dev.silverx.llmcraft.bridge.meteor;

import dev.silverx.llmcraft.config.ConfigManager;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.Module;

/** On/off switch for LLMCraft's automatic triggers, so they get a Meteor keybind and show in the module list. */
public final class LlmTriggersModule extends Module {
    public LlmTriggersModule() {
        super(MeteorBridge.CATEGORY, "llm-triggers",
                "LLMCraft's automatic triggers: chat patterns, death, join, interval (the same switch as /llm triggers on|off).");
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        set(true);
    }

    @Override
    public void onDeactivate() {
        set(false);
    }

    private static void set(boolean on) {
        var cfg = ConfigManager.get();
        if (cfg.triggers.enabled == on) return;
        cfg.triggers.enabled = on;
        ConfigManager.save();
    }

    /** Follows changes made with /llm triggers or in config.json. */
    static void sync() {
        Modules modules = Modules.get();
        if (modules == null) return;
        LlmTriggersModule m = modules.get(LlmTriggersModule.class);
        if (m != null && m.isActive() != ConfigManager.get().triggers.enabled) m.toggle();
    }
}
