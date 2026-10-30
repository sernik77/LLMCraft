package dev.silverx.llmcraft.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.silverx.llmcraft.LlmCraft;
import dev.silverx.llmcraft.agent.Agent;
import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.config.ConfigManager;
import dev.silverx.llmcraft.config.PermissionMode;
import dev.silverx.llmcraft.data.HistoryEntry;
import dev.silverx.llmcraft.data.Stores;
import dev.silverx.llmcraft.game.ChatLog;
import dev.silverx.llmcraft.llm.ProviderProfile;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolContext;
import dev.silverx.llmcraft.tool.ToolRegistry;
import dev.silverx.llmcraft.tool.ToolResult;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Runs inside a real client: exercises tools, mixins, commands and one full agent request against local Ollama. */
public class LlmCraftClientGameTest implements FabricClientGameTest {
    private static final String MODEL = System.getenv().getOrDefault("LLMCRAFT_TEST_MODEL", "qwen3.5:4b");

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            ctx.runOnClient(mc -> {
                var cfg = ConfigManager.get();
                for (Category c : Category.values()) cfg.singleplayerPermissions.put(c, PermissionMode.ALLOW);
                ProviderProfile p = cfg.profile("ollama");
                p.model = MODEL;
                p.extraBody.addProperty("think", false);
                p.temperature = 0;
                cfg.activeProfile = "ollama";
                cfg.control.stopOnPlayerInput = false;
                ConfigManager.save();
            });

            check(ctx, "player_status", "{}", "health");
            check(ctx, "world_info", "{}", "dimension minecraft:overworld");
            check(ctx, "inventory", "{}", "Selected hotbar slot");
            check(ctx, "find_blocks", "{\"block\":\"dirt\",\"radius\":8}", "dirt");
            check(ctx, "get_block", "{\"x\":0,\"y\":-64,\"z\":0}", "bedrock");
            check(ctx, "nearby_entities", "{}", "");
            check(ctx, "list_keybinds", "{\"filter\":\"llmcraft\"}", "key.llmcraft.prompt");
            check(ctx, "list_mods", "{\"filter\":\"llmcraft\"}", "llmcraft");
            check(ctx, "mod_info", "{\"id\":\"fabric-api\"}", "Fabric API");
            check(ctx, "list_client_commands", "{}", "Client commands");
            check(ctx, "read_client_log", "{\"lines\":20,\"filter\":\"LLMCraft\"}", "LLMCraft ready");
            check(ctx, "list_config_files", "{\"filter\":\"llmcraft\"}", "global_instructions.md");
            String denied = run(ctx, "read_config_file", "{\"path\":\"llmcraft/config.json\"}").text;
            assertTrue(denied.contains("private"), "config.json with API keys must be hidden, got: " + denied);

            check(ctx, "look", "{\"yaw\":90,\"pitch\":10}", "yaw 90.0");
            float yaw = ctx.computeOnClient(mc -> mc.player.getYRot());
            assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - 90)) < 1, "look failed, yaw=" + yaw);

            double x0 = ctx.computeOnClient(mc -> mc.player.getX());
            check(ctx, "move", "{\"directions\":[\"forward\"],\"seconds\":1}", "moved");
            double x1 = ctx.computeOnClient(mc -> mc.player.getX());
            assertTrue(Math.abs(x1 - x0) > 0.5, "move forward did not move the player (x " + x0 + " -> " + x1 + ")");

            check(ctx, "select_slot", "{\"slot\":3}", "selected slot 3");
            check(ctx, "jump", "{}", "jumped");

            // Raw key simulation through the KeyboardHandler invoker mixin: 'e' opens the inventory.
            check(ctx, "press_key", "{\"key\":\"e\"}", "pressed");
            ctx.waitTicks(5);
            boolean invOpen = ctx.computeOnClient(mc -> mc.screen instanceof InventoryScreen);
            assertTrue(invOpen, "press_key e did not open the inventory");
            check(ctx, "read_screen", "{}", "InventoryScreen");
            check(ctx, "click_slot", "{\"slot\":36,\"action\":\"pickup\"}", "slot 36");
            check(ctx, "close_screen", "{}", "closed");

            // Keybind tap through the KeyMapping accessor mixin.
            check(ctx, "press_keybind", "{\"id\":\"key.inventory\"}", "pressed key.inventory");
            ctx.waitTicks(3);
            assertTrue(ctx.computeOnClient(mc -> mc.screen instanceof InventoryScreen), "press_keybind key.inventory did not open the inventory");
            check(ctx, "close_screen", "{}", "closed");

            ToolResult shot = run(ctx, "screenshot", "{\"max_width\":512}");
            assertTrue(!shot.error && shot.images.size() == 1 && shot.images.getFirst().base64().length() > 1000, "screenshot failed: " + shot.text);

            check(ctx, "run_command", "{\"command\":\"time set day\"}", "ran /time set day");
            String blocked = ctx.computeOnClient(mc -> ToolRegistry.get("run_command").deny.apply(
                    new ToolContext(ConfigManager.get(), Stores.current(), ConfigManager.get().activeProfile()),
                    JsonParser.parseString("{\"command\":\"op Steve\"}").getAsJsonObject()));
            assertTrue(blocked != null && blocked.contains("blocked"), "op should be blocked by the gate, got: " + blocked);
            String self = ctx.computeOnClient(mc -> ToolRegistry.get("run_command").deny.apply(
                    new ToolContext(ConfigManager.get(), Stores.current(), ConfigManager.get().activeProfile()),
                    JsonParser.parseString("{\"command\":\"llm approve abc\"}").getAsJsonObject()));
            assertTrue(self != null, "the AI must not be able to run /llm");
            check(ctx, "send_chat", "{\"message\":\"hello from llmcraft test\"}", "sent 1");
            ctx.waitTicks(10);
            boolean echoed = ChatLog.recent(20, false).stream().anyMatch(l -> l.text().contains("hello from llmcraft test"));
            assertTrue(echoed, "sent chat message did not come back in the chat log");

            check(ctx, "remember", "{\"text\":\"Test base is at 0 64 0\",\"tag\":\"base\"}", "saved as #");
            check(ctx, "list_memory", "{}", "Test base is at 0 64 0");
            check(ctx, "remember", "{\"text\":\"Player likes oak\",\"scope\":\"global\"}", "saved as #");

            ctx.runOnClient(mc -> mc.getConnection().sendCommand("llm status"));
            ctx.waitTicks(5);

            // Prompt prefix must be swallowed locally, not sent to the server.
            ctx.runOnClient(mc -> mc.getConnection().sendChat("@ai"));
            ctx.waitTicks(5);
            boolean leaked = ChatLog.recent(30, false).stream().anyMatch(l -> l.kind() == ChatLog.Kind.SELF && l.text().trim().endsWith("@ai"));
            assertTrue(!leaked, "@ai prefix leaked to the server");

            ctx.takeScreenshot("llmcraft-tools");

            if (ollamaUp()) {
                int before = ctx.computeOnClient(mc -> Stores.current().history().size());
                ctx.runOnClient(mc -> Agent.submit("Call the player_status tool, then tell me my exact current health as a number.", Agent.Source.USER));
                ctx.waitFor(mc -> !Agent.busy(), 20 * 300);
                List<HistoryEntry> h = ctx.computeOnClient(mc -> Stores.current().history());
                assertTrue(h.size() >= before + 2, "agent did not record history");
                String answer = h.getLast().text;
                LlmCraft.LOGGER.info("[gametest] agent answer: {}", answer);
                assertTrue(answer.contains("player_status"), "agent did not use player_status: " + answer);
                assertTrue(answer.matches("(?s).*\\b20(\\.0)?\\b.*"), "agent answer lacks the health value 20: " + answer);
                ctx.takeScreenshot("llmcraft-agent");
            } else {
                LlmCraft.LOGGER.warn("[gametest] Ollama not reachable, skipping the agent round trip");
            }
        }
        LlmCraft.LOGGER.info("[gametest] ALL CHECKS PASSED");
    }

    private static ToolResult run(ClientGameTestContext ctx, String name, String json) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        CompletableFuture<ToolResult> f = ctx.computeOnClient(mc -> {
            Tool tool = ToolRegistry.get(name);
            if (tool == null) throw new AssertionError("missing tool " + name);
            ToolContext tc = new ToolContext(ConfigManager.get(), Stores.current(), ConfigManager.get().activeProfile());
            return tool.handler.apply(tc, args);
        });
        ctx.waitFor(mc -> f.isDone(), 20 * 60);
        ToolResult r = f.join();
        LlmCraft.LOGGER.info("[gametest] {} {} -> {}", name, json, r.text.length() > 300 ? r.text.substring(0, 300) + "…" : r.text);
        return r;
    }

    private static void check(ClientGameTestContext ctx, String name, String json, String expect) {
        ToolResult r = run(ctx, name, json);
        assertTrue(!r.error, name + " returned an error: " + r.text);
        assertTrue(r.text.contains(expect), name + " result lacks '" + expect + "': " + r.text);
    }

    private static void assertTrue(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    private static boolean ollamaUp() {
        try {
            var r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:11434/api/tags"))
                    .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains(MODEL);
        } catch (Exception e) {
            return false;
        }
    }
}
