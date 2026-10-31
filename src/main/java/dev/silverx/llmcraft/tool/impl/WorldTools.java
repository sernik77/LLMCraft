package dev.silverx.llmcraft.tool.impl;

import dev.silverx.llmcraft.agent.Permissions;
import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.config.PermissionMode;
import dev.silverx.llmcraft.game.BaritoneBridge;
import dev.silverx.llmcraft.game.Controller;
import dev.silverx.llmcraft.game.Inv;
import dev.silverx.llmcraft.game.Mining;
import dev.silverx.llmcraft.game.Script;
import dev.silverx.llmcraft.game.Snapshot;
import dev.silverx.llmcraft.tool.Args;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolContext;
import dev.silverx.llmcraft.tool.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Gathering, containers and furnaces as single calls. */
public final class WorldTools {
    private WorldTools() {}

    private static final Set<String> FURNACES = Set.of("furnace", "smoker", "blast_furnace");
    private static final List<Item> PREFERRED_FUEL = List.of(Items.COAL, Items.CHARCOAL, Items.COAL_BLOCK, Items.BLAZE_ROD, Items.DRIED_KELP_BLOCK);

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static CompletableFuture<ToolResult> err(String msg) {
        return CompletableFuture.completedFuture(ToolResult.error(msg));
    }

    private static boolean useBaritone(ToolContext ctx) {
        return BaritoneBridge.available() && Permissions.mode(Category.BARITONE, ctx.world) != PermissionMode.OFF;
    }

    public static void register(List<Tool> out) {
        out.add(Tool.builder("mine", Category.INTERACTION,
                        "Gather blocks: finds, walks to, breaks and picks up `count` blocks of a type (Baritone when installed, otherwise a simple walker). "
                                + "Checks that you have a tool that drops the block. Ore names include their deepslate variant; 'log' means any log.")
                .param("block", "string", "Block id(s), e.g. 'oak_log', 'log', 'iron_ore', 'stone', 'coal_ore diamond_ore'", true)
                .param("count", "integer", "How many blocks to break (default 1)", false)
                .param("timeout_seconds", "number", "Give up after this long (default 300)", false)
                .categoryFor((ctx, a) -> useBaritone(ctx) ? Category.BARITONE : Category.INTERACTION)
                .alsoOfferedUnder(Category.BARITONE)
                .summary(a -> "mine " + Args.integer(a, "count", 1) + " " + Args.str(a, "block", "?"))
                .async((ctx, a) -> {
                    var p = mc().player;
                    if (p == null) return err("not in a world");
                    Set<Block> blocks = Mining.resolve(Args.reqStr(a, "block"));
                    if (blocks.isEmpty()) return err("unknown block '" + Args.str(a, "block", "") + "'");
                    int count = Math.max(1, Math.min(2304, Args.integer(a, "count", 1)));
                    int timeout = (int) (Math.max(5, Args.num(a, "timeout_seconds", 300)) * 20);
                    List<String> noTool = new ArrayList<>();
                    for (Block b : blocks) {
                        if (!p.isCreative() && !Inv.canHarvest(b.defaultBlockState())) noTool.add(BuiltInRegistries.BLOCK.getKey(b).getPath() + " needs " + Inv.minimalTool(b.defaultBlockState()) + " or better");
                    }
                    if (!noTool.isEmpty() && noTool.size() == blocks.size()) {
                        return err("no tool in the inventory can harvest it (it would drop nothing): " + String.join("; ", noTool) + ". Craft the tool first.");
                    }
                    int before = Mining.broken(blocks);
                    long started = System.currentTimeMillis();
                    String names = Mining.ids(blocks);
                    if (useBaritone(ctx)) {
                        try {
                            if (!BaritoneBridge.execute("mine " + names)) return err("Baritone did not accept 'mine " + names + "'");
                        } catch (Throwable t) {
                            return err("Baritone failed: " + ModTools.rootMessage(t));
                        }
                        return Controller.run(Mining.baritoneWatch("mining " + names, () -> Mining.broken(blocks) - before >= count, () -> Mining.broken(blocks)), timeout)
                                .thenCompose(r -> Controller.run(Controller.waitTicks(15), 30).thenApply(x -> r)) // let drops get picked up
                                .thenApply(r -> {
                                    int got = Mining.broken(blocks) - before;
                                    String msg = "broke " + got + "/" + count + " (" + names + "): " + r + Mining.baritoneMessages(started, 4);
                                    return got >= count ? ToolResult.ok(msg) : ToolResult.error(msg);
                                });
                    }
                    return Controller.run(Mining.fallbackTask(blocks, count, ctx.config.context.maxScanRadius), timeout)
                            .thenApply(r -> {
                                int got = Mining.broken(blocks) - before;
                                String msg = "broke " + got + "/" + count + ": " + r;
                                return got >= count ? ToolResult.ok(msg) : ToolResult.error(msg);
                            });
                }).build());

        out.add(Tool.builder("use_container", Category.INTERACTION,
                        "Open a chest/barrel/shulker/hopper/dispenser... at a position (must be in reach), do one transfer and close it again - all in one call, no GUI handling. "
                                + "action: view (list contents), take/put (item + optional count), take_all, put_all (optionally only 'item').")
                .param("x", "integer", "X", true).param("y", "integer", "Y", true).param("z", "integer", "Z", true)
                .enumParam("action", "What to do (default view)", false, "view", "take", "put", "take_all", "put_all")
                .param("item", "string", "Item id for take/put (or filter for take_all/put_all)", false)
                .param("count", "integer", "For take/put: how many (default: all of that item)", false)
                .summary(a -> Args.str(a, "action", "view") + (Args.has(a, "item") ? " " + Args.str(a, "item", "") : "") + " container at "
                        + Args.integer(a, "x", 0) + " " + Args.integer(a, "y", 0) + " " + Args.integer(a, "z", 0))
                .async((ctx, a) -> {
                    var p = mc().player;
                    if (p == null) return err("not in a world");
                    BlockPos pos = new BlockPos(Args.integer(a, "x", 0), Args.integer(a, "y", 0), Args.integer(a, "z", 0));
                    if (mc().level.getBlockState(pos).isAir()) return err("there is no block at " + pos.toShortString());
                    double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
                    if (d > p.blockInteractionRange()) return err(String.format(Locale.ROOT, "out of reach (%.1f blocks) - walk closer first", d));
                    String action = Args.str(a, "action", "view").toLowerCase(Locale.ROOT);
                    String item = Args.str(a, "item", null);
                    if ((action.equals("take") || action.equals("put")) && (item == null || item.isBlank())) return err(action + " needs 'item'");
                    int count = Args.has(a, "count") ? Math.max(1, Args.integer(a, "count", 1)) : Integer.MAX_VALUE;
                    Script s = new Script("using container at " + pos.toShortString());
                    openContainer(s, pos);
                    String[] report = {""};
                    s.then("transferring items", 2, mc -> {
                        AbstractContainerMenu menu = Inv.menu();
                        report[0] = switch (action) {
                            case "take" -> "took " + transfer(menu, false, item, count);
                            case "put" -> "put " + transfer(menu, true, item, count);
                            case "take_all" -> "took " + transfer(menu, false, item, Integer.MAX_VALUE);
                            case "put_all" -> "put " + transfer(menu, true, item, Integer.MAX_VALUE);
                            default -> "";
                        };
                        return null;
                    });
                    s.waitTicks(3);
                    s.then(() -> {
                        AbstractContainerMenu menu = Inv.menu();
                        s.note((report[0].isEmpty() ? "" : report[0] + ". ") + "Container now holds: " + containerContents(menu));
                        p.closeContainer();
                        s.guard(null);
                    });
                    return Controller.run(s, 200).thenApply(ItemTools::fromScript);
                }).build());

        out.add(Tool.builder("smelt", Category.INTERACTION,
                        "Smelt/cook items in a furnace, smoker or blast furnace within reach (placed from the inventory if there is none nearby). "
                                + "Puts the items and fuel in, waits and collects the output - no GUI handling. Fuel is chosen automatically unless given.")
                .param("item", "string", "Item to smelt, e.g. 'raw_iron', 'beef', 'sand', 'oak_log'", true)
                .param("count", "integer", "How many (default: all of that item, max 64)", false)
                .param("fuel", "string", "Fuel item id (default: coal/charcoal, else planks/logs/other fuel)", false)
                .param("wait", "boolean", "Wait for the output and collect it (default true); false = load the furnace and leave", false)
                .summary(a -> "smelt " + (Args.has(a, "count") ? Args.integer(a, "count", 0) + " " : "") + Args.str(a, "item", "?"))
                .async((ctx, a) -> {
                    var p = mc().player;
                    if (p == null) return err("not in a world");
                    String q = Args.reqStr(a, "item");
                    int idx = Inv.find(q);
                    if (idx < 0) return err("no '" + q + "' in the inventory");
                    Item input = p.getInventory().getItem(idx).getItem();
                    int have = Inv.count(input);
                    int count = Math.min(64, Math.min(have, Args.has(a, "count") ? Math.max(1, Args.integer(a, "count", 1)) : have));
                    BlockPos furnace = Inv.findInReach(FURNACES);
                    Script s = new Script("smelting " + count + " " + Inv.id(input));
                    if (furnace == null) {
                        Item block = Inv.count(Items.FURNACE) > 0 ? Items.FURNACE : Inv.count(Items.SMOKER) > 0 ? Items.SMOKER : Inv.count(Items.BLAST_FURNACE) > 0 ? Items.BLAST_FURNACE : null;
                        if (block == null) return err("no furnace in reach or in the inventory (craft one: 8 cobblestone -> craft furnace)");
                        BlockPos placed = Inv.placeNearby(block);
                        if (placed == null) return err("no free spot to place the furnace next to you");
                        s.note("placed a " + Inv.id(block) + " at " + placed.toShortString());
                        s.until("placing the furnace", 20, () -> !mc().level.getBlockState(placed).isAir());
                        furnace = placed;
                    }
                    BlockPos at = furnace;
                    boolean wait = Args.bool(a, "wait", true);
                    String fuelQuery = Args.str(a, "fuel", null);
                    int[] loaded = {0};
                    int[] collected = {0};
                    int[] cookTicks = {200};
                    openContainer(s, at);
                    s.then("loading the furnace", 2, mc -> {
                        if (!(Inv.menu() instanceof AbstractFurnaceMenu menu)) return "that block is not a furnace";
                        cookTicks[0] = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(at).getBlock()).getPath().equals("furnace") ? 200 : 100;
                        if (menu.getSlot(2).hasItem()) Inv.click(menu, 2, 0, ClickType.QUICK_MOVE);
                        ItemStack in = menu.getSlot(0).getItem();
                        if (!in.isEmpty() && !in.is(input)) return "the furnace is already smelting " + Snapshot.stack(in) + "; take it out first";
                        int room = in.isEmpty() ? input.getDefaultMaxStackSize() : in.getMaxStackSize() - in.getCount();
                        loaded[0] = moveInto(menu, 0, input, Math.min(count, room));
                        if (loaded[0] == 0) return "could not put " + Inv.id(input) + " into the furnace";
                        int totalItems = loaded[0] + (in.isEmpty() ? 0 : in.getCount());
                        String fuelErr = addFuel(menu, fuelQuery, input, totalItems * cookTicks[0], cookTicks[0]);
                        if (fuelErr != null) return fuelErr;
                        return null;
                    });
                    if (wait) {
                        s.then("smelting", 20 * 300, mc -> {
                            if (!(Inv.menu() instanceof AbstractFurnaceMenu menu)) return "the furnace closed";
                            ItemStack result = menu.getSlot(2).getItem();
                            if (!result.isEmpty()) {
                                collected[0] += result.getCount();
                                Inv.click(menu, 2, 0, ClickType.QUICK_MOVE);
                            }
                            boolean inputLeft = menu.getSlot(0).hasItem();
                            if (collected[0] >= loaded[0] || !inputLeft && result.isEmpty() && !menu.isLit()) return null;
                            if (!menu.isLit() && inputLeft && !menu.getSlot(1).hasItem()) return "ran out of fuel with items left";
                            return Script.WAIT;
                        });
                    }
                    s.then(() -> {
                        s.note(wait ? "collected " + collected[0] + " smelted item(s) from " + loaded[0] + " " + Inv.id(input)
                                : "loaded " + loaded[0] + " " + Inv.id(input) + "; ready in about " + loaded[0] * cookTicks[0] / 20 + "s at " + at.toShortString()
                                + " (collect with use_container take_all)");
                        p.closeContainer();
                        s.guard(null);
                    });
                    int timeout = wait ? 20 * 20 + count * 220 : 20 * 15;
                    return Controller.run(s, timeout).thenApply(ItemTools::fromScript);
                }).build());
    }

    /** Opens the container block and waits until its menu arrives. */
    static void openContainer(Script s, BlockPos pos) {
        s.then("opening the container at " + pos.toShortString(), 40, new Script.Step() {
            boolean clicked;

            @Override
            public String tick(Minecraft mc) {
                var p = mc.player;
                if (!clicked) {
                    if (p.containerMenu != p.inventoryMenu) p.closeContainer();
                    Inv.useBlock(pos);
                    clicked = true;
                    return Script.WAIT;
                }
                if (p.containerMenu instanceof InventoryMenu) return Script.WAIT;
                s.opened(p.containerMenu);
                return null;
            }
        });
        s.waitTicks(2); // contents arrive right after the open packet
    }

    /** Moves up to {@code max} matching items between the container and the player inventory. */
    static String transfer(AbstractContainerMenu menu, boolean intoContainer, String query, int max) {
        Map<String, Integer> moved = new LinkedHashMap<>();
        int left = max;
        for (int i = 0; i < menu.slots.size() && left > 0; i++) {
            Slot sl = menu.slots.get(i);
            if (Inv.isPlayerSlot(sl) != intoContainer || !sl.hasItem()) continue;
            if (query != null && !query.isBlank() && !Inv.matches(sl.getItem(), query, true)) continue;
            ItemStack st = sl.getItem();
            String id = Inv.id(st);
            int n = st.getCount();
            if (n <= left) {
                Inv.click(menu, i, 0, ClickType.QUICK_MOVE);
                int moveDone = n - sl.getItem().getCount();
                if (moveDone <= 0) break; // destination full
                moved.merge(id, moveDone, Integer::sum);
                left -= moveDone;
            } else {
                int placed = moveCount(menu, i, !intoContainer, left);
                if (placed > 0) moved.merge(id, placed, Integer::sum);
                left -= placed;
                break;
            }
        }
        if (moved.isEmpty()) return "nothing" + (query == null ? "" : " matching '" + query + "'") + " (or no room)";
        List<String> parts = new ArrayList<>();
        moved.forEach((k, v) -> parts.add(v + " " + k));
        return String.join(", ", parts);
    }

    /** Moves exactly {@code n} items from slot {@code from} to the other side, one right click at a time. */
    private static int moveCount(AbstractContainerMenu menu, int from, boolean toPlayer, int n) {
        ItemStack src = menu.getSlot(from).getItem();
        int dst = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot sl = menu.slots.get(i);
            if (Inv.isPlayerSlot(sl) != toPlayer) continue;
            if (!sl.hasItem() || ItemStack.isSameItemSameComponents(sl.getItem(), src) && sl.getItem().getCount() + n <= sl.getItem().getMaxStackSize()) {
                if (sl.mayPlace(src)) {
                    dst = i;
                    break;
                }
            }
        }
        if (dst < 0) return 0;
        Inv.click(menu, from, 0, ClickType.PICKUP);
        int placed = 0;
        for (int k = 0; k < n && !menu.getCarried().isEmpty(); k++) {
            Inv.click(menu, dst, 1, ClickType.PICKUP);
            placed++;
        }
        if (!menu.getCarried().isEmpty()) Inv.click(menu, from, 0, ClickType.PICKUP);
        return placed;
    }

    /** Moves {@code n} of the item from the player inventory into container slot {@code slot}. */
    private static int moveInto(AbstractContainerMenu menu, int slot, Item item, int n) {
        int moved = 0;
        for (int i = 0; i < menu.slots.size() && moved < n; i++) {
            Slot sl = menu.slots.get(i);
            if (!Inv.isPlayerSlot(sl) || !sl.getItem().is(item)) continue;
            int take = Math.min(sl.getItem().getCount(), n - moved);
            Inv.click(menu, i, 0, ClickType.PICKUP);
            if (take == menu.getCarried().getCount()) {
                Inv.click(menu, slot, 0, ClickType.PICKUP);
            } else {
                for (int k = 0; k < take; k++) Inv.click(menu, slot, 1, ClickType.PICKUP);
            }
            if (!menu.getCarried().isEmpty()) Inv.click(menu, i, 0, ClickType.PICKUP);
            moved += take;
        }
        return moved;
    }

    private static String addFuel(AbstractContainerMenu menu, String query, Item input, int burnTicksNeeded, int cookTicks) {
        var fuels = mc().level.fuelValues();
        ItemStack inFuel = menu.getSlot(1).getItem();
        int burn = inFuel.isEmpty() ? 0 : fuels.burnDuration(inFuel) * inFuel.getCount();
        if (burn >= burnTicksNeeded) return null;
        Item fuel = null;
        if (!inFuel.isEmpty()) fuel = inFuel.getItem();
        else if (query != null && !query.isBlank()) {
            int i = Inv.find(query);
            if (i < 0) return "no fuel '" + query + "' in the inventory";
            fuel = mc().player.getInventory().getItem(i).getItem();
        } else {
            for (Item f : PREFERRED_FUEL) if (f != input && Inv.count(f) > 0) { fuel = f; break; }
            if (fuel == null) {
                var inv = mc().player.getInventory();
                int bestBurn = 0;
                for (int i = 0; i < 36; i++) {
                    ItemStack s = inv.getItem(i);
                    if (s.isEmpty() || s.is(input) || s.isDamageableItem() || s.is(Items.LAVA_BUCKET)) continue;
                    int b = fuels.burnDuration(s);
                    if (b > bestBurn) {
                        bestBurn = b;
                        fuel = s.getItem();
                    }
                }
            }
        }
        if (fuel == null) return "no fuel in the inventory (coal, charcoal, planks, logs, sticks...)";
        int per = fuels.burnDuration(new ItemStack(fuel));
        if (per <= 0) return Inv.id(fuel) + " is not a fuel";
        if (cookTicks < 200) per /= 2; // smokers and blast furnaces burn fuel twice as fast
        int need = Math.max(1, (int) Math.ceil((burnTicksNeeded - burn) / (double) Math.max(1, per)));
        int moved = moveInto(menu, 1, fuel, need);
        return moved > 0 ? null : "could not add fuel";
    }

    static String containerContents(AbstractContainerMenu menu) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int free = 0, total = 0;
        for (Slot sl : menu.slots) {
            if (Inv.isPlayerSlot(sl)) continue;
            total++;
            if (!sl.hasItem()) free++;
            else counts.merge(Inv.id(sl.getItem()), sl.getItem().getCount(), Integer::sum);
        }
        if (counts.isEmpty()) return "nothing (" + total + " empty slots)";
        List<String> parts = new ArrayList<>();
        counts.forEach((k, v) -> parts.add(v + " " + k));
        return String.join(", ", parts) + " (" + free + "/" + total + " slots free)";
    }
}
