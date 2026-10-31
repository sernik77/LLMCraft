package dev.silverx.llmcraft.bridge.gametest;

import dev.silverx.llmcraft.agent.Agent;
import dev.silverx.llmcraft.api.LlmCraftEvents;
import dev.silverx.llmcraft.bridge.Bridge;
import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.config.ConfigManager;
import dev.silverx.llmcraft.tool.ToolRegistry;
import dev.silverx.llmcraft.tool.ToolResult;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.utils.misc.MeteorStarscript;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.util.concurrent.CompletableFuture;

import static dev.silverx.llmcraft.bridge.gametest.T.assertTrue;
import static dev.silverx.llmcraft.bridge.gametest.T.check;
import static dev.silverx.llmcraft.bridge.gametest.T.deny;
import static dev.silverx.llmcraft.bridge.gametest.T.run;

/** Only loaded when Meteor Client is installed. */
final class MeteorChecks {
    private MeteorChecks() {}

    static void run(ClientGameTestContext ctx, boolean model) {
        check(ctx, "meteor", "{\"action\":\"modules\"}", "Render:");
        check(ctx, "meteor", "{\"action\":\"modules\",\"filter\":\"bright\"}", "fullbright");
        check(ctx, "meteor", "{\"action\":\"info\",\"module\":\"Fullbright\"}", "Fullbright");
        String unknown = T.run(ctx, "meteor", "{\"action\":\"info\",\"module\":\"fulbrite\"}").text;
        assertTrue(unknown.contains("Closest") && unknown.contains("fullbright"), "fuzzy suggestion missing: " + unknown);

        check(ctx, "meteor", "{\"action\":\"enable\",\"module\":\"fullbright\"}", "ON");
        assertTrue(ctx.computeOnClient(mc -> Modules.get().get("fullbright").isActive()), "fullbright did not turn on");
        check(ctx, "meteor", "{\"action\":\"disable\",\"module\":\"fullbright\"}", "OFF");

        // Flip the first boolean setting Meteor has and read it back from Meteor itself.
        String[] target = ctx.computeOnClient(mc -> {
            for (Module m : Modules.get().getAll()) {
                for (SettingGroup g : m.settings) {
                    for (Setting<?> s : g) {
                        if (s.get() instanceof Boolean b) return new String[]{m.name, s.name, String.valueOf(!b)};
                    }
                }
            }
            return null;
        });
        assertTrue(target != null, "no boolean setting found");
        check(ctx, "meteor", "{\"action\":\"set\",\"module\":\"" + target[0] + "\",\"setting\":\"" + target[1] + "\",\"value\":\"" + target[2] + "\"}", "->");
        String now = ctx.computeOnClient(mc -> String.valueOf(Modules.get().get(target[0]).settings.get(target[1]).get()));
        assertTrue(now.equals(target[2]), "setting " + target[0] + "." + target[1] + " is " + now + ", wanted " + target[2]);
        check(ctx, "meteor", "{\"action\":\"reset\",\"module\":\"" + target[0] + "\",\"setting\":\"" + target[1] + "\"}", "(default)");

        check(ctx, "meteor", "{\"action\":\"commands\"}", "fov");
        check(ctx, "meteor", "{\"action\":\"commands\",\"filter\":\"fov\"}", "usage");
        check(ctx, "meteor", "{\"action\":\"command\",\"text\":\".fov 75\"}", "ran .fov 75");
        int fov = ctx.computeOnClient(mc -> mc.options.fov().get());
        assertTrue(fov == 75, "fov command did not apply, fov=" + fov);
        ToolResult bad = T.run(ctx, "meteor", "{\"action\":\"command\",\"text\":\"fov notanumber\"}");
        assertTrue(bad.error && bad.text.contains("usage"), "bad command should fail with usage: " + bad.text);
        assertTrue(deny(ctx, "meteor", "{\"action\":\"command\",\"text\":\"reset all\"}") != null, "reset must be blocked");
        assertTrue(deny(ctx, "meteor", "{\"action\":\"command\",\"text\":\"ai hi\"}") != null, ".ai must be blocked for the AI");
        Category cat = ctx.computeOnClient(mc -> ToolRegistry.get("meteor").categoryFor(null, T.json("{\"action\":\"command\",\"text\":\"say hi\"}")));
        assertTrue(cat == Category.CHAT_SEND, ".say must count as public chat, got " + cat);

        // .ai / .llm exist and .llm reaches LLMCraft's /llm.
        assertTrue(ctx.computeOnClient(mc -> Commands.get("ai") != null), ".ai missing");
        ctx.runOnClient(mc -> {
            try {
                Commands.dispatch("llm status");
            } catch (Exception e) {
                throw new AssertionError(".llm status failed", e);
            }
        });

        // Starscript HUD variables.
        String status = ctx.computeOnClient(mc -> MeteorStarscript.run(MeteorStarscript.compile("{llm.status}")));
        assertTrue(status != null && status.startsWith("idle"), "{llm.status} = " + status);

        // llm-triggers follows /llm triggers and writes it back.
        ctx.runOnClient(mc -> ConfigManager.get().triggers.enabled = true);
        ctx.waitTicks(3);
        assertTrue(ctx.computeOnClient(mc -> Modules.get().get("llm-triggers").isActive()), "llm-triggers did not follow the config");
        ctx.runOnClient(mc -> Modules.get().get("llm-triggers").toggle());
        ctx.waitTicks(2);
        assertTrue(!ctx.computeOnClient(mc -> ConfigManager.get().triggers.enabled), "llm-triggers off did not turn triggers off");

        // llm-guard pauses Freecam while the AI works and restores it after.
        ctx.runOnClient(mc -> {
            Modules.get().get("llm-guard").toggle();
            Modules.get().get(Freecam.class).toggle();
        });
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> LlmCraftEvents.requestStarted("test", Agent.Source.USER));
        ctx.waitTicks(3);
        assertTrue(!ctx.computeOnClient(mc -> Modules.get().get(Freecam.class).isActive()), "llm-guard did not pause Freecam");
        ctx.runOnClient(mc -> LlmCraftEvents.requestFinished("test", Agent.Source.USER, "", "done"));
        ctx.waitTicks(3);
        assertTrue(ctx.computeOnClient(mc -> Modules.get().get(Freecam.class).isActive()), "llm-guard did not restore Freecam");
        ctx.runOnClient(mc -> {
            Modules.get().get(Freecam.class).toggle();
            Modules.get().get("llm-guard").toggle();
        });
        Bridge.LOGGER.info("[gametest] Meteor checks passed");

        if (!model) return;
        // The model drives Meteor through the meteor tool.
        ctx.runOnClient(mc -> Modules.get().get("fullbright").disable());
        CompletableFuture<String> req = ctx.computeOnClient(mc -> Agent.request("Turn on the Meteor module fullbright (use the meteor tool).", Agent.Source.USER));
        ctx.waitFor(mc -> req.isDone(), 20 * 180);
        Bridge.LOGGER.info("[gametest] agent reply: {}", req.getNow("?"));
        assertTrue(ctx.computeOnClient(mc -> Modules.get().get("fullbright").isActive()), "the model did not turn on fullbright");
        Bridge.LOGGER.info("[gametest] Meteor agent check passed");
    }
}
