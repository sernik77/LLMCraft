package dev.silverx.llmcraft.bridge.gametest;

import dev.silverx.llmcraft.agent.Agent;
import dev.silverx.llmcraft.bridge.Bridge;
import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.config.ConfigManager;
import dev.silverx.llmcraft.config.PermissionMode;
import dev.silverx.llmcraft.llm.ProviderProfile;
import dev.silverx.llmcraft.tool.ToolRegistry;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import static dev.silverx.llmcraft.bridge.gametest.T.assertTrue;

/**
 * Runs in a real client. With both mods (default) every bridge path runs, plus agent round trips against local Ollama;
 * -PnoMeteor / -PnoJsMacros check that the bridge loads and stays out of the way without them.
 */
public class BridgeClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            ctx.runOnClient(mc -> {
                var cfg = ConfigManager.get();
                for (Category c : Category.values()) cfg.singleplayerPermissions.put(c, PermissionMode.ALLOW);
                ProviderProfile p = cfg.profile("ollama");
                p.model = T.MODEL;
                p.extraBody.addProperty("think", false);
                p.temperature = 0;
                cfg.activeProfile = "ollama";
                cfg.control.stopOnPlayerInput = false;
                cfg.triggers.enabled = false;
                ConfigManager.save();
            });
            boolean meteor = Bridge.meteor(), jsMacros = Bridge.jsMacros();
            Bridge.LOGGER.info("[gametest] Meteor: {}, JsMacros: {}", meteor, jsMacros);
            assertTrue((ToolRegistry.get("meteor") != null) == meteor, "meteor tool registered=" + (ToolRegistry.get("meteor") != null) + " but Meteor installed=" + meteor);
            assertTrue((ToolRegistry.get("jsmacros") != null) == jsMacros, "jsmacros tool registered=" + (ToolRegistry.get("jsmacros") != null) + " but JsMacros installed=" + jsMacros);
            // The core keeps addon tools whichever mod initialized first.
            assertTrue(ToolRegistry.get("craft") != null, "core tools missing");

            boolean model = T.ollamaUp();
            if (!model) Bridge.LOGGER.warn("[gametest] Ollama with {} not reachable, skipping the agent round trips", T.MODEL);
            if (meteor) MeteorChecks.run(ctx, model);
            if (jsMacros) JsMacrosChecks.run(ctx, model);
            assertTrue(ctx.computeOnClient(mc -> !Agent.busy()), "agent still busy at the end");
        }
        Bridge.LOGGER.info("[gametest] ALL BRIDGE CHECKS PASSED");
    }
}
