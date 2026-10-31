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
import net.minecraft.world.item.Items;

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

            // ---- world-changing control tools (server gives items / spawns a target) ----
            sp.getServer().runCommand("give Player0 minecraft:dirt 16");
            sp.getServer().runCommand("gamerule doMobSpawning false");
            ctx.waitTicks(5);
            int[] p0 = ctx.computeOnClient(mc -> new int[]{mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ()});
            String target = "{\"x\":" + (p0[0] + 1) + ",\"y\":" + (p0[1] - 1) + ",\"z\":" + p0[2] + "}";
            check(ctx, "mine_block", target, "broken");
            assertTrue(ctx.computeOnClient(mc -> mc.level.getBlockState(new net.minecraft.core.BlockPos(p0[0] + 1, p0[1] - 1, p0[2])).isAir()), "mine_block left the block");
            int dirtSlot = ctx.computeOnClient(mc -> {
                var inv = mc.player.getInventory();
                for (int i = 0; i < 9; i++) if (inv.getItem(i).is(net.minecraft.world.item.Items.DIRT)) return i;
                return -1;
            });
            assertTrue(dirtSlot >= 0, "dirt was not given");
            check(ctx, "place_block", target.replace("}", ",\"item\":\"dirt\"}"), "Dirt");
            check(ctx, "walk_to", "{\"x\":" + (p0[0] + 5.5) + ",\"z\":" + (p0[2] + 0.5) + "}", "arrived");
            sp.getServer().runCommand("summon minecraft:pig " + (p0[0] + 7) + " " + p0[1] + " " + p0[2] + " {NoAI:1b}");
            ctx.waitTicks(10);
            int pigId = ctx.computeOnClient(mc -> mc.level.getEntitiesOfClass(net.minecraft.world.entity.animal.pig.Pig.class, mc.player.getBoundingBox().inflate(6)).stream()
                    .findFirst().map(net.minecraft.world.entity.Entity::getId).orElse(-1));
            assertTrue(pigId >= 0, "pig not found");
            check(ctx, "attack", "{\"entity_id\":" + pigId + "}", "hit");
            ctx.waitTicks(5);
            float pigHp = ctx.computeOnClient(mc -> ((net.minecraft.world.entity.LivingEntity) mc.level.getEntity(pigId)).getHealth());
            assertTrue(pigHp < 10, "attack did not damage the pig (hp " + pigHp + ")");
            check(ctx, "use_item", "{}", "right clicked");

            // ---- high-level item tools, in survival, without the model touching any GUI ----
            sp.getServer().runCommand("gamemode survival Player0");
            sp.getServer().runCommand("clear Player0");
            sp.getServer().runCommand("recipe take Player0 *"); // nothing unlocked: everything comes from the integrated server
            sp.getServer().runCommand("give Player0 minecraft:oak_log 4");
            ctx.waitTicks(10);
            check(ctx, "recipe_info", "{\"item\":\"wooden_pickaxe\"}", "Can craft now");
            check(ctx, "craft", "{\"item\":\"wooden_pickaxe\"}", "crafted");
            assertTrue(count(ctx, Items.WOODEN_PICKAXE) == 1, "craft did not produce a wooden pickaxe");
            assertTrue(ctx.computeOnClient(mc -> mc.screen == null && mc.player.containerMenu == mc.player.inventoryMenu), "crafting left a window open");
            assertTrue(count(ctx, Items.CRAFTING_TABLE) == 1, "craft did not pick the crafting table it placed back up");

            // asking for an item you already hold still crafts a new one
            sp.getServer().runCommand("give Player0 minecraft:oak_log 2");
            ctx.waitTicks(5);
            check(ctx, "craft", "{\"item\":\"wooden_pickaxe\"}", "1 wooden_pickaxe");
            assertTrue(count(ctx, Items.WOODEN_PICKAXE) == 2, "second craft did not add a wooden pickaxe");
            sp.getServer().runCommand("clear Player0 minecraft:wooden_pickaxe 1");

            // multiplayer-like: only the recipe book, recipes unlock step by step while crafting
            ctx.runOnClient(mc -> dev.silverx.llmcraft.game.Crafting.useServerRecipes = false);
            sp.getServer().runCommand("clear Player0");
            sp.getServer().runCommand("advancement revoke Player0 everything");
            sp.getServer().runCommand("recipe take Player0 *");
            sp.getServer().runCommand("give Player0 minecraft:oak_log 3");
            ctx.waitTicks(10);
            String locked = run(ctx, "craft", "{\"item\":\"wooden_pickaxe\"}").text;
            assertTrue(locked.contains("grid"), "a locked recipe should point to the grid fallback: " + locked);
            check(ctx, "craft", "{\"item\":\"oak_planks\",\"count\":8}", "crafted 8 oak_planks");
            ctx.waitTicks(5);
            check(ctx, "craft", "{\"item\":\"stick\"}", "stick");
            ctx.waitTicks(5);
            check(ctx, "craft", "{\"item\":\"wooden_pickaxe\"}", "1 wooden_pickaxe");
            ctx.runOnClient(mc -> dev.silverx.llmcraft.game.Crafting.useServerRecipes = true);
            sp.getServer().runCommand("recipe take Player0 *");

            // manual pattern
            sp.getServer().runCommand("give Player0 minecraft:oak_log 1");
            ctx.waitTicks(5);
            int planksBefore = count(ctx, Items.OAK_PLANKS);
            check(ctx, "craft", "{\"item\":\"oak_planks\",\"grid\":[[\"oak_log\"]]}", "crafted 4 oak_planks");
            assertTrue(count(ctx, Items.OAK_PLANKS) == planksBefore + 4, "grid craft did not add 4 planks");

            // recipe book path (packet placement), 3x3 at the table placed above
            sp.getServer().runCommand("recipe give Player0 *");
            sp.getServer().runCommand("give Player0 minecraft:oak_log 2");
            ctx.waitTicks(10);
            check(ctx, "craft", "{\"item\":\"chest\"}", "1 chest");
            assertTrue(count(ctx, Items.CHEST) == 1, "book craft did not produce a chest");

            sp.getServer().runCommand("give Player0 minecraft:iron_helmet 1");
            sp.getServer().runCommand("give Player0 minecraft:dirt 5");
            ctx.waitTicks(5);
            check(ctx, "equip", "{\"item\":\"iron_helmet\"}", "wearing");
            ctx.waitTicks(3);
            assertTrue(ctx.computeOnClient(mc -> mc.player.getInventory().getItem(39).is(Items.IRON_HELMET)), "helmet not worn");
            check(ctx, "equip", "{\"item\":\"wooden_pickaxe\"}", "holding");
            int dirtBefore = count(ctx, Items.DIRT);
            check(ctx, "drop_items", "{\"item\":\"dirt\",\"count\":2}", "dropped 2");
            ctx.waitTicks(5);
            assertTrue(count(ctx, Items.DIRT) == dirtBefore - 2, "drop_items did not drop 2 dirt");

            int[] p1 = ctx.computeOnClient(mc -> new int[]{mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ()});
            String chestPos = (p1[0] - 2) + " " + p1[1] + " " + p1[2];
            sp.getServer().runCommand("setblock " + chestPos + " minecraft:chest");
            sp.getServer().runCommand("item replace block " + chestPos + " container.0 with minecraft:cobblestone 10");
            ctx.waitTicks(10);
            String chestArgs = "\"x\":" + (p1[0] - 2) + ",\"y\":" + p1[1] + ",\"z\":" + p1[2];
            check(ctx, "use_container", "{" + chestArgs + "}", "10 cobblestone");
            check(ctx, "use_container", "{" + chestArgs + ",\"action\":\"take\",\"item\":\"cobblestone\",\"count\":4}", "took 4 cobblestone");
            ctx.waitTicks(5);
            assertTrue(count(ctx, Items.COBBLESTONE) == 4, "use_container take did not move 4 cobblestone");
            check(ctx, "use_container", "{" + chestArgs + ",\"action\":\"put\",\"item\":\"dirt\"}", " dirt. Container now holds");
            assertTrue(ctx.computeOnClient(mc -> mc.player.containerMenu == mc.player.inventoryMenu), "use_container left the chest open");

            sp.getServer().runCommand("give Player0 minecraft:furnace 1");
            sp.getServer().runCommand("give Player0 minecraft:raw_iron 2");
            sp.getServer().runCommand("give Player0 minecraft:coal 1");
            ctx.waitTicks(5);
            check(ctx, "smelt", "{\"item\":\"raw_iron\"}", "collected 2");
            assertTrue(count(ctx, Items.IRON_INGOT) == 2, "smelt did not give 2 iron ingots");
            assertTrue(count(ctx, Items.FURNACE) == 1, "smelt did not pick the furnace it placed back up");

            // Too little fuel: load only what it covers, never burn the crafting table, leave nothing in the furnace.
            for (String junk : new String[]{"oak_planks", "stick", "oak_log", "coal"}) sp.getServer().runCommand("clear Player0 minecraft:" + junk);
            sp.getServer().runCommand("give Player0 minecraft:raw_iron 3");
            sp.getServer().runCommand("give Player0 minecraft:oak_planks 1");
            ctx.waitTicks(5);
            check(ctx, "smelt", "{\"item\":\"raw_iron\"}", "fuel only covers 1");
            assertTrue(count(ctx, Items.IRON_INGOT) == 3 && count(ctx, Items.RAW_IRON) == 2, "low-fuel smelt should give 1 ingot and keep 2 raw iron");
            assertTrue(count(ctx, Items.CRAFTING_TABLE) == 1, "smelt burned the crafting table");
            sp.getServer().runCommand("clear Player0 minecraft:raw_iron");

            // Item names mine the source block; "diamond" never means diamond_block.
            assertTrue(ctx.computeOnClient(mc -> {
                var set = dev.silverx.llmcraft.game.Mining.resolve("diamond");
                return set.contains(net.minecraft.world.level.block.Blocks.DIAMOND_ORE) && !set.contains(net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK);
            }), "resolve('diamond') should mean the ores");
            String diamond = run(ctx, "mine", "{\"block\":\"diamond\",\"count\":3}").text;
            assertTrue(diamond.contains("iron_pickaxe"), "mine diamond should ask for an iron pickaxe first: " + diamond);

            // Plan: everything down to the blocks, tools before the blocks that need them, logs in one trip.
            String plan = run(ctx, "plan", "{\"goal\":\"nether_portal\"}").text;
            assertTrue(plan.contains("mine 10 obsidian") && plan.contains("craft 1 diamond_pickaxe") && plan.contains("flint_and_steel"), "portal plan incomplete: " + plan);
            assertTrue(plan.indexOf("craft 1 iron_pickaxe") < plan.indexOf("mine 3 diamond") && plan.indexOf("craft 1 diamond_pickaxe") < plan.indexOf("mine 10 obsidian"),
                    "portal plan out of order: " + plan);
            assertTrue(plan.split("mine \\d+ log").length <= 2, "logs should be gathered in one step: " + plan);
            assertTrue(!plan.contains("water_bucket") && !plan.contains("lava_bucket") && !plan.contains("mine 3 diamond  [with diamond_pickaxe"),
                    "portal plan casts obsidian or uses a pickaxe before making it: " + plan);

            // Placing never clicks a block that opens a screen.
            int[] q = ctx.computeOnClient(mc -> new int[]{mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ()});
            String tablePos = q[0] + " " + q[1] + " " + (q[2] + 2);
            sp.getServer().runCommand("setblock " + tablePos + " minecraft:crafting_table");
            sp.getServer().runCommand("give Player0 minecraft:dirt 4");
            ctx.waitTicks(10);
            String onTable = run(ctx, "place_block", "{\"x\":" + q[0] + ",\"y\":" + (q[1] + 1) + ",\"z\":" + (q[2] + 2) + ",\"item\":\"dirt\"}").text;
            ctx.waitTicks(5);
            assertTrue(onTable.contains("open/toggle") && ctx.computeOnClient(mc -> mc.screen == null), "place_block against a crafting table: " + onTable);
            sp.getServer().runCommand("setblock " + tablePos + " minecraft:air");
            ctx.waitTicks(5);

            String obsidian = run(ctx, "mine", "{\"block\":\"obsidian\"}").text;
            assertTrue(obsidian.contains("diamond_pickaxe"), "mine should refuse obsidian without a diamond pickaxe: " + obsidian);
            check(ctx, "mine", "{\"block\":\"grass_block\",\"count\":2,\"timeout_seconds\":40}", "got 2/2");
            if (dev.silverx.llmcraft.game.BaritoneBridge.available()) {
                LlmCraft.LOGGER.info("[gametest] Baritone present: testing the Baritone path of mine");
                check(ctx, "mine", "{\"block\":\"grass_block\",\"count\":3,\"timeout_seconds\":50}", "Baritone said");
                ctx.waitTicks(20);
                assertTrue(!dev.silverx.llmcraft.game.BaritoneBridge.isBusy(), "Baritone still running after mine finished");
                check(ctx, "baritone", "{\"command\":\"goto " + (p1[0] + 3) + " " + p1[1] + " " + p1[2] + "\",\"timeout_seconds\":30}", "baritone 'goto");
            }

            // Portal in one call: frame with filler corners, lit.
            sp.getServer().runCommand("give Player0 minecraft:obsidian 10");
            sp.getServer().runCommand("give Player0 minecraft:flint_and_steel 1");
            sp.getServer().runCommand("give Player0 minecraft:cobblestone 4");
            ctx.waitTicks(10);
            check(ctx, "build_nether_portal", "{}", "built and lit");
            assertTrue(count(ctx, Items.OBSIDIAN) == 0, "portal did not use the 10 obsidian");

            ctx.takeScreenshot("llmcraft-tools");

            int toolChars = ctx.computeOnClient(mc -> {
                int n = 0;
                for (var t : Agent.offeredTools()) n += t.name().length() + t.description().length() + t.parameters().toString().length();
                return n;
            });
            LlmCraft.LOGGER.info("[gametest] offered tool schemas: {} chars", toolChars);

            if (ollamaUp()) {
                // ASK flow: approve through the same path a chat-button click takes.
                ctx.runOnClient(mc -> {
                    ConfigManager.get().singleplayerPermissions.put(Category.CHAT_SEND, PermissionMode.ASK);
                    Agent.submit("Use the send_chat tool to say exactly: approval test ok", Agent.Source.USER);
                });
                ctx.waitFor(mc -> dev.silverx.llmcraft.agent.Approvals.pendingCount() > 0, 20 * 180);
                ctx.runOnClient(mc -> mc.getConnection().sendUnattendedCommand("llm approve " + dev.silverx.llmcraft.agent.Approvals.latestId(), null));
                ctx.waitFor(mc -> !Agent.busy(), 20 * 180);
                ctx.waitTicks(10);
                boolean approvedSent = ChatLog.recent(30, false).stream().anyMatch(l -> l.text().toLowerCase().contains("approval test ok"));
                assertTrue(approvedSent, "approved send_chat did not reach the chat");

                // Deny via the deny key path.
                ctx.runOnClient(mc -> Agent.submit("Use the send_chat tool to say exactly: denied test", Agent.Source.USER));
                ctx.waitFor(mc -> dev.silverx.llmcraft.agent.Approvals.pendingCount() > 0, 20 * 180);
                ctx.runOnClient(mc -> dev.silverx.llmcraft.agent.Approvals.denyLatest());
                ctx.waitFor(mc -> !Agent.busy(), 20 * 180);
                ctx.waitTicks(10);
                boolean deniedSent = ChatLog.recent(30, false).stream().anyMatch(l -> l.text().toLowerCase().contains("denied test"));
                assertTrue(!deniedSent, "denied send_chat was sent anyway");
                String deniedHistory = ctx.computeOnClient(mc -> Stores.current().history().getLast().text);
                LlmCraft.LOGGER.info("[gametest] after deny: {}", deniedHistory);
                assertTrue(deniedHistory.contains("denied"), "denial not recorded: " + deniedHistory);
                ctx.runOnClient(mc -> ConfigManager.get().singleplayerPermissions.put(Category.CHAT_SEND, PermissionMode.ALLOW));

                // Long history must still fit the default context window.
                ctx.runOnClient(mc -> {
                    var w = Stores.current();
                    for (int i = 0; i < 38; i++) {
                        w.addHistory(new HistoryEntry(i % 2 == 0 ? "user" : "assistant",
                                "Turn " + i + ": we talked about building a big oak house near the river at 120 70 -340, gathering iron and planning a nether portal. ".repeat(3)));
                    }
                });
                int before = ctx.computeOnClient(mc -> Stores.current().history().size());
                ctx.runOnClient(mc -> Agent.submit("Call the player_status tool, then tell me my exact current health as a number.", Agent.Source.USER));
                ctx.waitFor(mc -> !Agent.busy(), 20 * 300);
                List<HistoryEntry> h = ctx.computeOnClient(mc -> Stores.current().history());
                assertTrue(h.size() >= before + 2, "agent did not record history");
                String answer = h.getLast().text;
                LlmCraft.LOGGER.info("[gametest] agent answer: {}", answer);
                int promptTokens = Agent.lastPromptTokens();
                int ctxTokens = ctx.computeOnClient(mc -> ConfigManager.get().activeProfile().contextTokens);
                LlmCraft.LOGGER.info("[gametest] prompt tokens {} / context {}", promptTokens, ctxTokens);
                assertTrue(promptTokens > 0 && promptTokens < ctxTokens, "prompt does not fit the context window: " + promptTokens + " / " + ctxTokens);
                assertTrue(answer.contains("player_status"), "agent did not use player_status: " + answer);
                assertTrue(answer.matches("(?s).*\\b20(\\.0)?\\b.*"), "agent answer lacks the health value 20: " + answer);

                // End to end: "craft a pickaxe" from logs must go through the craft tool, not GUI clicks.
                sp.getServer().runCommand("clear Player0");
                sp.getServer().runCommand("recipe take Player0 *");
                sp.getServer().runCommand("give Player0 minecraft:oak_log 4");
                ctx.waitTicks(10);
                ctx.runOnClient(mc -> Agent.submit("Craft me a wooden pickaxe.", Agent.Source.USER));
                ctx.waitFor(mc -> !Agent.busy(), 20 * 300);
                String craftHistory = ctx.computeOnClient(mc -> Stores.current().history().getLast().text);
                LlmCraft.LOGGER.info("[gametest] agent craft: {}", craftHistory);
                assertTrue(count(ctx, Items.WOODEN_PICKAXE) == 1, "agent did not craft a wooden pickaxe: " + craftHistory);
                assertTrue(!craftHistory.contains("click_slot") && !craftHistory.contains("press_key"), "agent used GUI tools: " + craftHistory);

                // Step limit: the request ends with a summary instead of an abrupt stop.
                ctx.runOnClient(mc -> {
                    ConfigManager.get().agent.maxSteps = 1;
                    Agent.submit("Call the inventory tool and tell me what I have.", Agent.Source.USER);
                });
                ctx.waitFor(mc -> !Agent.busy(), 20 * 300);
                String wrapped = ctx.computeOnClient(mc -> Stores.current().history().getLast().text);
                LlmCraft.LOGGER.info("[gametest] after step limit: {}", wrapped);
                assertTrue(!wrapped.isBlank() && !wrapped.equals("(no reply)") && wrapped.contains("inventory"), "no summary after the step limit: " + wrapped);
                ctx.runOnClient(mc -> ConfigManager.get().agent.maxSteps = 32);
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

    private static int count(ClientGameTestContext ctx, net.minecraft.world.item.Item item) {
        return ctx.computeOnClient(mc -> mc.player.getInventory().countItem(item));
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
