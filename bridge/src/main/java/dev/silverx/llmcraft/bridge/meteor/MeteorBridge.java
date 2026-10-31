package dev.silverx.llmcraft.bridge.meteor;

import dev.silverx.llmcraft.agent.Agent;
import dev.silverx.llmcraft.api.LlmCraftEvents;
import dev.silverx.llmcraft.bridge.Bridge;
import dev.silverx.llmcraft.config.ConfigManager;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.MeteorStarscript;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;
import org.meteordev.starscript.value.Value;
import org.meteordev.starscript.value.ValueMap;

/**
 * Meteor's side ("meteor" entrypoint, only created by Meteor itself): the .ai/.llm commands, the LLMCraft module
 * category and the {llm.*} Starscript variables for HUD text.
 */
public final class MeteorBridge extends MeteorAddon {
    public static final Category CATEGORY = new Category("LLMCraft", Items.WRITABLE_BOOK.getDefaultInstance());

    static volatile String lastReply = "";
    static volatile String lastTool = "";

    @Override
    public void onInitialize() {
        Commands.add(new AiCommand());
        Commands.add(new LlmCommand());

        Modules.get().add(new LlmTriggersModule());
        LlmGuardModule guard = new LlmGuardModule();
        Modules.get().add(guard);

        LlmCraftEvents.register(new LlmCraftEvents.Listener() {
            @Override
            public void requestStarted(String prompt, Agent.Source source) {
                Minecraft.getInstance().execute(guard::pause);
            }

            @Override
            public void toolCalled(String tool, String summary, String result, boolean error) {
                lastTool = summary + (error ? " (failed)" : "");
            }

            @Override
            public void reply(String text) {
                lastReply = text.replace('\n', ' ');
            }

            @Override
            public void requestFinished(String prompt, Agent.Source source, String reply, String outcome) {
                Minecraft.getInstance().execute(guard::resume);
            }

            @Override
            public void cancelled(String reason) {
                Minecraft.getInstance().execute(guard::resume);
            }
        });

        // /llm triggers on|off and the module stay in step.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> LlmTriggersModule.sync());

        if (MeteorStarscript.ss != null) {
            MeteorStarscript.ss.set("llm", new ValueMap()
                    .set("status", () -> Value.string(Agent.status()))
                    .set("busy", () -> Value.bool(Agent.busy()))
                    .set("enabled", () -> Value.bool(ConfigManager.get().enabled))
                    .set("profile", () -> Value.string(profileField(true)))
                    .set("model", () -> Value.string(profileField(false)))
                    .set("reply", () -> Value.string(lastReply))
                    .set("tool", () -> Value.string(lastTool))
                    .set("tokens", () -> Value.number(Agent.lastPromptTokens())));
        }
        Bridge.LOGGER.info("Meteor integration ready: .ai, .llm, modules llm-triggers/llm-guard, {llm.*} Starscript");
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.silverx.llmcraft.bridge.meteor";
    }

    private static String profileField(boolean name) {
        var p = ConfigManager.get().activeProfile();
        if (p == null) return "";
        return name ? p.name : p.model;
    }
}
