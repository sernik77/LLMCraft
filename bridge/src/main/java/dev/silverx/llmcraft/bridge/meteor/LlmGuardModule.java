package dev.silverx.llmcraft.bridge.meteor;

import meteordevelopment.meteorclient.settings.ModuleListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.movement.AutoWalk;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;

import java.util.ArrayList;
import java.util.List;

/** While the AI works, turns off Meteor modules that fight its control (Freecam eats movement input) and turns them back on after. */
public final class LlmGuardModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<Module>> pauseModules = sgGeneral.add(new ModuleListSetting.Builder()
            .name("pause-modules")
            .description("Turned off while the AI is working and back on when it is done.")
            .defaultValue(Freecam.class, AutoWalk.class)
            .build());

    private final List<Module> paused = new ArrayList<>();

    public LlmGuardModule() {
        super(MeteorBridge.CATEGORY, "llm-guard", "Pauses Meteor modules that get in the way of the LLMCraft AI (Freecam, AutoWalk, ...) while it works.");
    }

    /** Client thread. */
    void pause() {
        if (!isActive()) return;
        for (Module m : pauseModules.get()) {
            if (m != this && m.isActive() && !paused.contains(m)) {
                m.toggle();
                paused.add(m);
            }
        }
        if (!paused.isEmpty()) info("Paused for the AI: %s", String.join(", ", paused.stream().map(m -> m.title).toList()));
    }

    /** Client thread. */
    void resume() {
        for (Module m : paused) if (!m.isActive()) m.toggle();
        paused.clear();
    }

    @Override
    public void onDeactivate() {
        resume();
    }
}
