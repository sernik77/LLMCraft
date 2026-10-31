package dev.silverx.llmcraft.game;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Crafting without the model touching a GUI. Recipes from the player's recipe book are placed with the
 * recipe book packet (the server moves the ingredients); recipes the book does not know yet - in
 * singleplayer they come from the integrated server, or as a pattern from the model - are laid out with
 * ordinary slot clicks, which the server accepts for any valid pattern. 2x2 recipes need no screen at
 * all; 3x3 recipes use a crafting table in reach (placed from the inventory, or crafted first).
 * Missing intermediates (planks, sticks, the table...) are crafted automatically.
 */
public final class Crafting {
    private Crafting() {}

    private static final Set<String> TABLE = Set.of("crafting_table");

    /**
     * One way to make an item. {@code cells} lists, row by row over {@code width} columns, the items
     * accepted in each grid cell (empty list = empty cell). {@code bookId} is set when the recipe book has it.
     */
    public record Recipe(RecipeDisplayId bookId, Item result, int resultCount, int width, int height,
                         List<Ingredient> ingredients, List<List<Item>> cells) {
        boolean fits2x2() {
            return width <= 2 && height <= 2;
        }

        public String describe() {
            Map<String, Integer> need = new LinkedHashMap<>();
            for (Ingredient i : ingredients) need.merge(ingredientName(i), 1, Integer::sum);
            String in = need.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
            return resultCount + " " + Inv.id(result) + " <- " + in + (fits2x2() ? " (2x2, no table needed)" : " (needs crafting table)")
                    + (bookId == null ? " [not in recipe book yet]" : "");
        }
    }

    public record Job(Recipe recipe, int times) {}

    public record Plan(List<Job> jobs, boolean needsTable, String error) {}

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    // ---------------------------------------------------------------- recipes

    private static Map<Item, List<Recipe>> index;
    private static long indexBuiltAt;

    /** Crafting recipes that produce the item; recipe book ones first. */
    public static List<Recipe> recipesFor(Item item) {
        return index().getOrDefault(item, List.of());
    }

    /** Index by result item (rebuilt at most every 2 seconds - the recipe book grows while playing). */
    private static Map<Item, List<Recipe>> index() {
        long now = System.currentTimeMillis();
        if (index != null && now - indexBuiltAt < 2000) return index;
        Map<Item, List<Recipe>> out = new HashMap<>();
        var p = mc().player;
        if (p != null) {
            ContextMap ctx = SlotDisplayContext.fromLevel(mc().level);
            Map<RecipeDisplay, RecipeDisplayId> book = new HashMap<>();
            for (var collection : p.getRecipeBook().getCollections()) {
                for (RecipeDisplayEntry e : collection.getRecipes()) {
                    if (e.craftingRequirements().isEmpty()) continue;
                    book.put(e.display(), e.id());
                    Recipe r = fromDisplay(e.id(), e.display(), e.craftingRequirements().get(), ctx);
                    if (r != null) out.computeIfAbsent(r.result, k -> new ArrayList<>()).add(r);
                }
            }
            // Singleplayer: the integrated server knows every recipe, including ones not unlocked yet.
            var server = mc().getSingleplayerServer();
            if (server != null) {
                for (var holder : server.getRecipeManager().getRecipes()) {
                    if (!(holder.value() instanceof CraftingRecipe cr)) continue;
                    var placement = cr.placementInfo();
                    if (placement.isImpossibleToPlace()) continue;
                    for (RecipeDisplay d : cr.display()) {
                        if (book.containsKey(d)) continue;
                        Recipe r = fromDisplay(null, d, placement.ingredients(), ctx);
                        if (r != null) out.computeIfAbsent(r.result, k -> new ArrayList<>()).add(r);
                    }
                }
            }
        }
        index = out;
        indexBuiltAt = now;
        return out;
    }

    private static Recipe fromDisplay(RecipeDisplayId id, RecipeDisplay d, List<Ingredient> reqs, ContextMap ctx) {
        List<List<Item>> cells = new ArrayList<>();
        int w, h;
        if (d instanceof ShapedCraftingRecipeDisplay s) {
            w = s.width();
            h = s.height();
            for (SlotDisplay sd : s.ingredients()) cells.add(items(sd, ctx));
        } else if (d instanceof ShapelessCraftingRecipeDisplay s) {
            int n = s.ingredients().size();
            w = n <= 4 ? 2 : 3;
            h = (n + w - 1) / w;
            for (SlotDisplay sd : s.ingredients()) cells.add(items(sd, ctx));
            if (n <= 2) h = 1;
        } else {
            return null;
        }
        if (reqs.isEmpty()) return null;
        ItemStack res = d.result().resolveForFirstStack(ctx);
        if (res.isEmpty()) return null;
        return new Recipe(id, res.getItem(), Math.max(1, res.getCount()), w, h, reqs, cells);
    }

    private static List<Item> items(SlotDisplay sd, ContextMap ctx) {
        List<Item> out = new ArrayList<>();
        for (ItemStack st : sd.resolveForStacks(ctx)) if (!st.isEmpty() && !out.contains(st.getItem())) out.add(st.getItem());
        return out;
    }

    static String ingredientName(Ingredient i) {
        List<Holder<Item>> opts = i.items().toList();
        if (opts.isEmpty()) return "?";
        if (opts.size() == 1) return Inv.id(opts.getFirst().value());
        // e.g. any planks: show the common suffix
        String first = Inv.id(opts.getFirst().value());
        int us = first.lastIndexOf('_');
        String suffix = us >= 0 ? first.substring(us + 1) : first;
        boolean all = opts.stream().allMatch(h -> Inv.id(h.value()).endsWith(suffix));
        return all ? "any " + suffix : first + " (or " + (opts.size() - 1) + " alternatives)";
    }

    // ---------------------------------------------------------------- planning

    public static Plan plan(Item target, int count) {
        boolean tableNear = Inv.findInReach(TABLE) != null;
        Planner pl = new Planner();
        List<String> missing = new ArrayList<>();
        if (!pl.consume(target, count, 0, new HashSet<>(), missing)) {
            return new Plan(List.of(), false, missingText(target, missing));
        }
        boolean needsTable = pl.jobs.stream().anyMatch(j -> !j.recipe.fits2x2());
        if (needsTable && !tableNear && Inv.count(Items.CRAFTING_TABLE) <= 0) {
            // Craft (and later place) a table first, then plan the target with what is left.
            Planner withTable = new Planner();
            List<String> m2 = new ArrayList<>();
            if (!withTable.consume(Items.CRAFTING_TABLE, 1, 0, new HashSet<>(), m2)) {
                return new Plan(List.of(), true, "needs a crafting table and cannot make one: " + missingText(Items.CRAFTING_TABLE, m2));
            }
            List<String> m3 = new ArrayList<>();
            if (!withTable.consume(target, count, 0, new HashSet<>(), m3)) {
                return new Plan(List.of(), true, "not enough materials for both a crafting table and the item: " + missingText(target, m3));
            }
            return new Plan(withTable.jobs, true, null);
        }
        return new Plan(pl.jobs, needsTable, null);
    }

    private static String missingText(Item target, List<String> missing) {
        if (recipesFor(target).isEmpty()) {
            return "no known crafting recipe for " + Inv.id(target) + " (not in your recipe book yet - recipes unlock when you first get an ingredient)."
                    + " If you know the recipe, call craft with a 'grid' pattern; if it is smelted, use smelt.";
        }
        return "missing " + (missing.isEmpty() ? "ingredients" : String.join(", ", missing));
    }

    private static final class Planner {
        final Map<Item, Integer> sim = new HashMap<>();
        final List<Job> jobs = new ArrayList<>();

        Planner() {
            var inv = mc().player.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty()) sim.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }

        /** Makes {@code n} of the item available (crafting what is missing) and uses them up. */
        boolean consume(Item item, int n, int depth, Set<Item> stack, List<String> missing) {
            int have = sim.getOrDefault(item, 0);
            int take = Math.min(have, n);
            sim.put(item, have - take);
            n -= take;
            if (n <= 0) return true;
            if (depth > 5 || stack.contains(item)) {
                missing.add(n + " " + Inv.id(item));
                sim.merge(item, take, Integer::sum);
                return false;
            }
            List<Recipe> recipes = recipesFor(item);
            if (recipes.isEmpty()) {
                missing.add(n + " " + Inv.id(item));
                sim.merge(item, take, Integer::sum);
                return false;
            }
            List<String> firstMissing = null;
            for (Recipe r : recipes) {
                Map<Item, Integer> simBackup = new HashMap<>(sim);
                int jobsBackup = jobs.size();
                List<String> m = new ArrayList<>();
                int times = (n + r.resultCount - 1) / r.resultCount;
                stack.add(item);
                boolean ok = true;
                for (Ingredient ing : r.ingredients) {
                    if (!consumeIngredient(ing, times, depth + 1, stack, m)) {
                        ok = false;
                        break;
                    }
                }
                stack.remove(item);
                if (ok) {
                    jobs.add(new Job(r, times));
                    sim.merge(item, times * r.resultCount - n, Integer::sum);
                    return true;
                }
                sim.clear();
                sim.putAll(simBackup);
                while (jobs.size() > jobsBackup) jobs.removeLast();
                if (firstMissing == null) firstMissing = m;
            }
            sim.merge(item, take, Integer::sum);
            if (firstMissing != null) missing.addAll(firstMissing);
            return false;
        }

        boolean consumeIngredient(Ingredient ing, int n, int depth, Set<Item> stack, List<String> missing) {
            // use whatever matching items are already there
            for (var e : sim.entrySet()) {
                if (n <= 0) break;
                if (e.getValue() > 0 && ing.test(new ItemStack(e.getKey()))) {
                    int t = Math.min(e.getValue(), n);
                    e.setValue(e.getValue() - t);
                    n -= t;
                }
            }
            if (n <= 0) return true;
            List<String> firstMissing = null;
            for (Holder<Item> h : ing.items().toList()) {
                Item opt = h.value();
                if (recipesFor(opt).isEmpty()) continue;
                Map<Item, Integer> simBackup = new HashMap<>(sim);
                int jobsBackup = jobs.size();
                List<String> m = new ArrayList<>();
                if (consume(opt, n, depth, stack, m)) return true;
                sim.clear();
                sim.putAll(simBackup);
                while (jobs.size() > jobsBackup) jobs.removeLast();
                if (firstMissing == null) firstMissing = m;
            }
            missing.addAll(firstMissing != null ? firstMissing : List.of(n + " " + ingredientName(ing)));
            return false;
        }
    }

    // ---------------------------------------------------------------- execution

    /** Builds the action that runs a plan. The script reports what it crafted. */
    public static Script craftTask(Plan plan, Item target, int count) {
        Script s = new Script("crafting " + count + " " + Inv.id(target));
        Map<String, Integer> made = new LinkedHashMap<>();
        for (Job job : plan.jobs) {
            Recipe r = job.recipe;
            ensureMenu(s, !r.fits2x2());
            for (int i = 0; i < job.times; i++) {
                if (r.bookId != null) {
                    s.then(() -> mc().gameMode.handlePlaceRecipe(Inv.menu().containerId, r.bookId, false));
                } else {
                    s.then("laying out " + Inv.id(r.result), 2, mc -> placeCells(r.cells, r.width));
                }
                s.then("waiting for the server to show " + Inv.id(r.result) + " as the crafting result", 30, mc -> {
                    ItemStack res = Inv.menu().getSlot(0).getItem();
                    return res.is(r.result) ? null : Script.WAIT;
                });
                takeResult(s, made);
            }
        }
        finish(s, made);
        return s;
    }

    /**
     * Manual crafting with a pattern given by the model. {@code grid} is rows of item ids, "" = empty.
     */
    public static Script gridTask(List<List<String>> grid, int times) {
        // trim to the used area so small patterns can use the 2x2 inventory grid
        int minR = 9, maxR = -1, minC = 9, maxC = -1;
        for (int r = 0; r < grid.size(); r++)
            for (int c = 0; c < grid.get(r).size(); c++)
                if (!grid.get(r).get(c).isBlank()) {
                    minR = Math.min(minR, r);
                    maxR = Math.max(maxR, r);
                    minC = Math.min(minC, c);
                    maxC = Math.max(maxC, c);
                }
        Script s = new Script("crafting from a pattern");
        if (maxR < 0) return s.then("checking the pattern", 1, mc -> "the grid is empty");
        int h = maxR - minR + 1, w = maxC - minC + 1;
        if (h > 3 || w > 3) return s.then("checking the pattern", 1, mc -> "the grid is larger than 3x3");
        List<List<Item>> cells = new ArrayList<>();
        for (int r = 0; r < h; r++)
            for (int c = 0; c < w; c++) {
                List<String> row = grid.get(minR + r);
                String id = row.size() > minC + c ? row.get(minC + c) : "";
                if (id.isBlank()) {
                    cells.add(List.of());
                    continue;
                }
                Item it = Inv.item(id);
                if (it == null) return s.then("checking the pattern", 1, mc -> "unknown item '" + id + "' in the grid");
                cells.add(List.of(it));
            }
        Map<String, Integer> made = new LinkedHashMap<>();
        ensureMenu(s, h > 2 || w > 2);
        for (int t = 0; t < times; t++) {
            s.then("placing the pattern", 2, mc -> placeCells(cells, w));
            s.then("waiting for a crafting result (is the pattern a valid recipe?)", 30, mc -> Inv.menu().getSlot(0).getItem().isEmpty() ? Script.WAIT : null);
            takeResult(s, made);
        }
        finish(s, made);
        return s;
    }

    /** Puts one item into each non-empty cell with left/right clicks. Returns an error or null. */
    private static String placeCells(List<List<Item>> cells, int width) {
        AbstractContainerMenu menu = Inv.menu();
        int gridWidth = menu instanceof CraftingMenu ? 3 : 2;
        for (int i = 0; i < cells.size(); i++) {
            List<Item> accepted = cells.get(i);
            if (accepted.isEmpty()) continue;
            int src = -1;
            for (int k = 0; k < menu.slots.size() && src < 0; k++) {
                Slot sl = menu.slots.get(k);
                if (Inv.isPlayerSlot(sl) && sl.hasItem() && accepted.contains(sl.getItem().getItem())) src = k;
            }
            if (src < 0) return "ran out of " + Inv.id(accepted.getFirst()) + " while laying out the recipe";
            int dst = 1 + (i / width) * gridWidth + i % width;
            Inv.click(menu, src, 0, ClickType.PICKUP);
            Inv.click(menu, dst, 1, ClickType.PICKUP);
            if (!menu.getCarried().isEmpty()) Inv.click(menu, src, 0, ClickType.PICKUP);
        }
        return null;
    }

    private static void takeResult(Script s, Map<String, Integer> made) {
        ItemStack[] taken = {ItemStack.EMPTY};
        s.then(() -> {
            taken[0] = Inv.menu().getSlot(0).getItem().copy();
            Inv.click(Inv.menu(), 0, 0, ClickType.QUICK_MOVE);
        });
        s.then("taking the crafted item (is the inventory full?)", 20, mc -> {
            if (!Inv.menu().getSlot(0).getItem().isEmpty()) return Script.WAIT;
            if (!taken[0].isEmpty()) made.merge(Inv.id(taken[0]), taken[0].getCount(), Integer::sum);
            return null;
        });
    }

    /** Makes sure the right crafting menu is the active one before the next steps run. */
    private static void ensureMenu(Script s, boolean needsTable) {
        s.then(() -> s.guard(null)); // switching menus is expected here
        s.then("preparing the crafting grid", 80, new Script.Step() {
            BlockPos placed;
            boolean usedTable;
            int wait;

            @Override
            public String tick(Minecraft mc) {
                var p = mc.player;
                AbstractContainerMenu menu = p.containerMenu;
                if (menu instanceof CraftingMenu) {
                    s.guard(menu);
                    return null;
                }
                if (!(menu instanceof InventoryMenu)) {
                    p.closeContainer();
                    return Script.WAIT;
                }
                if (!needsTable) {
                    s.guard(menu);
                    return null;
                }
                if (usedTable) {
                    if (++wait > 40) return "the crafting table did not open (is something blocking it?)";
                    return Script.WAIT;
                }
                BlockPos table = Inv.findInReach(TABLE);
                if (table == null && placed == null) {
                    if (Inv.count(Items.CRAFTING_TABLE) <= 0) return "a 3x3 recipe needs a crafting table and there is none in reach or in the inventory";
                    placed = Inv.placeNearby(Items.CRAFTING_TABLE);
                    if (placed == null) return "no free spot to place a crafting table next to you";
                    s.note("placed a crafting table at " + placed.toShortString());
                    return Script.WAIT;
                }
                if (table == null) {
                    if (++wait > 20) return "the crafting table did not appear after placing it";
                    return Script.WAIT;
                }
                Inv.useBlock(table);
                usedTable = true;
                wait = 0;
                return Script.WAIT;
            }
        });
        s.then(() -> {
            AbstractContainerMenu menu = mc().player.containerMenu;
            if (menu instanceof CraftingMenu && s.openedMenu() == null) s.opened(menu);
        });
    }

    private static void finish(Script s, Map<String, Integer> made) {
        s.then("clearing the crafting grid", 10, mc -> {
            AbstractContainerMenu menu = Inv.menu();
            int gridEnd = menu instanceof CraftingMenu ? 10 : menu instanceof InventoryMenu ? 5 : 0;
            for (int i = 1; i < gridEnd; i++) if (menu.getSlot(i).hasItem()) Inv.click(menu, i, 0, ClickType.QUICK_MOVE);
            if (!menu.getCarried().isEmpty()) {
                // put a stray carried stack back anywhere in the inventory
                for (int i = 0; i < menu.slots.size(); i++) {
                    Slot sl = menu.slots.get(i);
                    if (Inv.isPlayerSlot(sl) && !sl.hasItem()) {
                        Inv.click(menu, i, 0, ClickType.PICKUP);
                        break;
                    }
                }
            }
            return null;
        });
        s.then(() -> {
            if (s.openedMenu() != null && mc().player.containerMenu == s.openedMenu()) mc().player.closeContainer();
            s.guard(null);
            String list = made.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
            s.note(list.isEmpty() ? "nothing crafted" : "crafted " + list);
        });
    }
}
