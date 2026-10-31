package dev.silverx.llmcraft.tool.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.game.Controller;
import dev.silverx.llmcraft.game.Crafting;
import dev.silverx.llmcraft.game.Inv;
import dev.silverx.llmcraft.game.Script;
import dev.silverx.llmcraft.game.Snapshot;
import dev.silverx.llmcraft.tool.Args;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Item work by name - no GUI, no slot numbers for the model to get wrong. */
public final class ItemTools {
    private ItemTools() {}

    /** Foods that hurt or are wasteful; eaten only when nothing else is there. */
    private static final Set<Item> BAD_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
            Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.CHICKEN);

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static CompletableFuture<ToolResult> err(String msg) {
        return CompletableFuture.completedFuture(ToolResult.error(msg));
    }

    static ToolResult fromScript(String r) {
        return Script.failed(r) ? ToolResult.error(r.startsWith(Script.FAILED) ? r.substring(Script.FAILED.length()) : r) : ToolResult.ok(r);
    }

    public static void register(List<Tool> out) {
        out.add(Tool.builder("craft", Category.INVENTORY,
                        "Craft an item directly (no GUI needed). Missing intermediates like planks, sticks or a crafting table are crafted automatically; "
                                + "a crafting table is used/placed when the recipe needs 3x3. Uses the player's recipe book. For a recipe the book does not have, pass 'grid'.")
                .param("item", "string", "Item id to make, e.g. 'wooden_pickaxe', 'torch', 'oak_planks'", true)
                .param("count", "integer", "How many items you want (default 1)", false)
                .rawParam("grid", gridSchema(), false)
                .summary(a -> "craft " + Args.integer(a, "count", 1) + " " + Args.str(a, "item", "?"))
                .async((ctx, a) -> {
                    if (mc().player == null) return err("not in a world");
                    int count = Math.max(1, Math.min(576, Args.integer(a, "count", 1)));
                    if (a.has("grid") && a.get("grid").isJsonArray() && !a.getAsJsonArray("grid").isEmpty()) {
                        List<List<String>> grid = parseGrid(a.getAsJsonArray("grid"));
                        return Controller.run(Crafting.gridTask(grid, Math.min(64, count)), 20 * 60).thenApply(ItemTools::fromScript);
                    }
                    Item item = Inv.item(Args.reqStr(a, "item"));
                    if (item == null) return err("unknown item '" + Args.str(a, "item", "") + "' (use the exact id, e.g. oak_planks)");
                    Crafting.Plan plan = Crafting.plan(item, count);
                    if (plan.error() != null) return err(plan.error());
                    return Controller.run(Crafting.craftTask(plan, item, count), 20 * 120).thenApply(ItemTools::fromScript);
                }).build());

        out.add(Tool.builder("recipe_info", Category.PLAYER_READ,
                        "How an item is crafted (from the player's recipe book): ingredients, whether a crafting table is needed, and whether it can be crafted right now with what is in the inventory.")
                .param("item", "string", "Item id", true)
                .sync((ctx, a) -> {
                    if (mc().player == null) return ToolResult.error("not in a world");
                    Item item = Inv.item(Args.reqStr(a, "item"));
                    if (item == null) return ToolResult.error("unknown item '" + Args.str(a, "item", "") + "'");
                    List<Crafting.Recipe> recipes = Crafting.recipesFor(item);
                    if (recipes.isEmpty()) return ToolResult.ok("No unlocked crafting recipe for " + Inv.id(item)
                            + " in the recipe book (it may be smelted, found, or not unlocked yet - craft accepts a manual 'grid').");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(5, recipes.size()); i++) sb.append("- ").append(recipes.get(i).describe()).append('\n');
                    if (recipes.size() > 5) sb.append("(+").append(recipes.size() - 5).append(" more variants)\n");
                    Crafting.Plan plan = Crafting.plan(item, 1);
                    if (plan.error() != null) sb.append("Cannot craft 1 now: ").append(plan.error());
                    else {
                        sb.append("Can craft now. Steps: ");
                        List<String> steps = new ArrayList<>();
                        for (Crafting.Job j : plan.jobs()) steps.add(j.times() + "x " + Inv.id(j.recipe().result()));
                        sb.append(String.join(" -> ", steps));
                        if (plan.needsTable()) sb.append(" (uses a crafting table)");
                    }
                    return ToolResult.ok(sb.toString().trim());
                }).build());

        out.add(Tool.builder("equip", Category.INVENTORY,
                        "Put an item to use by name: into the main hand (tools, weapons, blocks, food), offhand, or an armor slot. Armor goes to its own slot automatically.")
                .param("item", "string", "Item id or part of it, e.g. 'iron_pickaxe', 'shield', 'diamond_chestplate'", true)
                .enumParam("slot", "Default: auto (armor -> armor slot, everything else -> main hand)", false, "auto", "hand", "offhand", "head", "chest", "legs", "feet")
                .sync((ctx, a) -> {
                    var p = mc().player;
                    if (p == null) return ToolResult.error("not in a world");
                    String q = Args.reqStr(a, "item");
                    int idx = Inv.find(q);
                    if (idx < 0) return ToolResult.error("no '" + q + "' in the inventory. Inventory: " + Snapshot.inventorySummary(p, 30));
                    Inventory inv = p.getInventory();
                    ItemStack stack = inv.getItem(idx);
                    String slot = Args.str(a, "slot", "auto").toLowerCase(Locale.ROOT);
                    var equippable = stack.get(DataComponents.EQUIPPABLE);
                    if (slot.equals("auto")) slot = equippable != null && equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? equippable.slot().getName() : "hand";
                    String what = Snapshot.stack(stack);
                    switch (slot) {
                        case "hand", "mainhand" -> {
                            String e = Inv.toHand(idx);
                            if (e != null) return ToolResult.error(e);
                            return ToolResult.ok("holding " + Snapshot.stack(p.getMainHandItem()) + " (hotbar slot " + inv.getSelectedSlot() + ")");
                        }
                        case "offhand" -> {
                            if (idx == 40) return ToolResult.ok(what + " is already in the offhand");
                            AbstractContainerMenu menu = Inv.menu();
                            Inv.click(menu, Inv.menuSlot(menu, idx), 40, ClickType.SWAP);
                            return ToolResult.ok("moved " + what + " to the offhand");
                        }
                        case "head", "chest", "legs", "feet" -> {
                            int armorIdx = switch (slot) {
                                case "feet" -> 36;
                                case "legs" -> 37;
                                case "chest" -> 38;
                                default -> 39;
                            };
                            if (equippable == null || !equippable.slot().getName().equals(slot)) {
                                return ToolResult.error(what + " cannot be worn on the " + slot);
                            }
                            if (idx == armorIdx) return ToolResult.ok(what + " is already worn");
                            AbstractContainerMenu menu = Inv.menu();
                            int from = Inv.menuSlot(menu, idx), to = Inv.menuSlot(menu, armorIdx);
                            if (from < 0 || to < 0) return ToolResult.error("close the open container first");
                            Inv.click(menu, from, 0, ClickType.PICKUP);
                            Inv.click(menu, to, 0, ClickType.PICKUP);
                            if (!menu.getCarried().isEmpty()) Inv.click(menu, from, 0, ClickType.PICKUP); // the old piece goes back
                            return ToolResult.ok("wearing " + what + " on the " + slot);
                        }
                        default -> {
                            return ToolResult.error("unknown slot '" + slot + "'");
                        }
                    }
                }).build());

        out.add(Tool.builder("drop_items", Category.INVENTORY, "Throw items out of the inventory by name (no GUI). Without item: drops the held item.")
                .param("item", "string", "Item id (default: the held item)", false)
                .param("count", "integer", "How many (default: all of that item)", false)
                .summary(a -> "drop " + (Args.has(a, "count") ? Args.integer(a, "count", 0) + " " : "all ") + Args.str(a, "item", "held item"))
                .sync((ctx, a) -> {
                    var p = mc().player;
                    if (p == null) return ToolResult.error("not in a world");
                    Inventory inv = p.getInventory();
                    String q = Args.str(a, "item", null);
                    if (q == null || q.isBlank()) {
                        if (p.getMainHandItem().isEmpty()) return ToolResult.error("hand is empty");
                        q = Inv.id(p.getMainHandItem());
                    }
                    int want = Args.has(a, "count") ? Math.max(1, Args.integer(a, "count", 1)) : Integer.MAX_VALUE;
                    AbstractContainerMenu menu = Inv.menu();
                    int dropped = 0;
                    for (int idx = 0; idx <= 40 && dropped < want; idx++) {
                        ItemStack s = inv.getItem(idx);
                        if (!Inv.matches(s, q, false)) continue;
                        int slot = Inv.menuSlot(menu, idx);
                        if (slot < 0) continue;
                        int n = s.getCount();
                        if (n <= want - dropped) {
                            Inv.click(menu, slot, 1, ClickType.THROW);
                            dropped += n;
                        } else {
                            while (dropped < want) {
                                Inv.click(menu, slot, 0, ClickType.THROW);
                                dropped++;
                            }
                        }
                    }
                    if (dropped == 0) return ToolResult.error("no '" + q + "' in the inventory");
                    return ToolResult.ok("dropped " + dropped + " " + q);
                }).build());

        out.add(Tool.builder("eat", Category.INTERACTION, "Eat food from the inventory (picks a good food automatically unless item is given) and wait until it is eaten.")
                .param("item", "string", "Food item id (optional)", false)
                .async((ctx, a) -> {
                    var p = mc().player;
                    if (p == null) return err("not in a world");
                    Inventory inv = p.getInventory();
                    int best = -1;
                    int bestScore = Integer.MIN_VALUE;
                    String q = Args.str(a, "item", null);
                    for (int i = 0; i < 36; i++) {
                        ItemStack s = inv.getItem(i);
                        var food = s.get(DataComponents.FOOD);
                        if (food == null) continue;
                        if (q != null && !q.isBlank() && !Inv.matches(s, q, true)) continue;
                        int score = food.nutrition() * 10 + (int) (food.saturation() * 4) - (BAD_FOOD.contains(s.getItem()) ? 1000 : 0);
                        if (score > bestScore) {
                            bestScore = score;
                            best = i;
                        }
                    }
                    if (best < 0) return err(q == null ? "no food in the inventory" : "no '" + q + "' food in the inventory");
                    ItemStack food = inv.getItem(best);
                    var props = food.get(DataComponents.FOOD);
                    if (p.getFoodData().getFoodLevel() >= 20 && !props.canAlwaysEat() && !p.isCreative()) return err("not hungry (food 20/20)");
                    String e = Inv.toHand(best);
                    if (e != null) return err(e);
                    String name = Snapshot.stack(food);
                    var consumable = food.get(DataComponents.CONSUMABLE);
                    int ticks = consumable == null ? 40 : Math.max(10, Math.round(consumable.consumeSeconds() * 20)) + 6;
                    int before = p.getFoodData().getFoodLevel();
                    return Controller.run(Controller.holdFor(List.of(mc().options.keyUse), ticks), ticks + 20)
                            .thenApply(r -> ToolResult.ok("ate " + name + ": food " + before + " -> " + mc().player.getFoodData().getFoodLevel()));
                }).build());
    }

    private static JsonObject gridSchema() {
        JsonObject cell = new JsonObject();
        cell.addProperty("type", "string");
        JsonObject row = new JsonObject();
        row.addProperty("type", "array");
        row.add("items", cell);
        JsonObject grid = new JsonObject();
        grid.addProperty("type", "array");
        grid.add("items", row);
        grid.addProperty("description", "Optional manual pattern for recipes the recipe book lacks: up to 3 rows of up to 3 item ids, \"\" = empty cell, "
                + "e.g. [[\"oak_planks\",\"oak_planks\"],[\"oak_planks\",\"oak_planks\"]]");
        return grid;
    }

    static List<List<String>> parseGrid(JsonArray rows) {
        List<List<String>> grid = new ArrayList<>();
        for (JsonElement row : rows) {
            List<String> cells = new ArrayList<>();
            if (row.isJsonArray()) {
                for (JsonElement c : row.getAsJsonArray()) cells.add(c.isJsonNull() ? "" : c.getAsString().trim());
            } else if (row.isJsonPrimitive()) {
                // "oak_planks,oak_planks,"
                for (String c : row.getAsString().split(",", -1)) cells.add(c.trim());
            }
            grid.add(cells);
        }
        return grid;
    }
}
