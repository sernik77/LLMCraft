package dev.silverx.llmcraft.game;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Works out everything a goal needs, down to the blocks to mine: crafting trees, smelting (with fuel),
 * the pickaxe each block needs (crafted first when missing) and what only mobs drop. What the inventory
 * already holds is used first. The result is an ordered checklist for the model.
 */
public final class Planning {
    private Planning() {}

    public record Goal(Item item, int count) {}

    /** Named goals that are not a single item. */
    public static final Map<String, List<String>> COMPOSITES = Map.of(
            "nether_portal", List.of("10 obsidian", "1 flint_and_steel"),
            "portal", List.of("10 obsidian", "1 flint_and_steel"),
            "iron_tools", List.of("1 iron_pickaxe", "1 iron_axe", "1 iron_sword", "1 iron_shovel"),
            "diamond_tools", List.of("1 diamond_pickaxe", "1 diamond_axe", "1 diamond_sword", "1 diamond_shovel"),
            "iron_armor", List.of("1 iron_helmet", "1 iron_chestplate", "1 iron_leggings", "1 iron_boots"),
            "diamond_armor", List.of("1 diamond_helmet", "1 diamond_chestplate", "1 diamond_leggings", "1 diamond_boots"));

    private enum Kind { MINE, SMELT, CRAFT, HUNT, UNKNOWN }

    private static final class Action {
        final Kind kind;
        final String what;
        int count;
        String extra;
        /** MINE: a tool this plan crafts first (the action cannot move before that craft). */
        Item tool;

        Action(Kind kind, String what, int count, String extra) {
            this.kind = kind;
            this.what = what;
            this.count = count;
            this.extra = extra;
        }
    }

    private final Map<Item, Integer> sim = new HashMap<>();
    private final Map<String, Integer> used = new LinkedHashMap<>();
    private final List<Action> actions = new ArrayList<>();
    private final Set<Item> goalItems = new HashSet<>();
    private final Set<Item> tools = new HashSet<>();
    private int smelted;
    private boolean needsTable;

    public static String plan(List<Goal> goals) {
        Planning p = new Planning();
        var inv = Minecraft.getInstance().player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) p.sim.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        for (Goal g : goals) p.goalItems.add(g.item);
        for (Goal g : goals) p.need(g.item, g.count, 0, new HashSet<>(), true);
        p.planFuel();
        p.batchMining();
        return p.render(goals);
    }

    /** Makes {@code n} of the item available. {@code top}: a goal item (shown even when crafted). */
    private void need(Item item, int n, int depth, Set<Item> stack, boolean top) {
        int have = sim.getOrDefault(item, 0);
        int take = Math.min(have, n);
        if (take > 0) {
            sim.put(item, have - take);
            used.merge(Inv.id(item), take, Integer::sum);
            n -= take;
        }
        if (n <= 0) return;
        if (depth > 10 || stack.contains(item)) {
            add(Kind.UNKNOWN, Inv.id(item), n, null);
            return;
        }
        stack.add(item);
        try {
            Sources.Mined explicit = Sources.alias(Inv.id(item));
            Sources.Mined self = explicit == null && isExplicitSelfMined(item) ? Sources.mined(item) : null;
            List<Crafting.Recipe> recipes = Crafting.recipesFor(item);
            Crafting.Recipe ready = readyRecipe(recipes, n);
            if (ready != null) {
                craft(item, ready, n, depth, stack, top);
                return;
            }
            if (explicit != null || self != null) {
                mine(item, n, explicit != null ? explicit : self, depth, stack);
                return;
            }
            List<Item> smeltIn = Sources.smeltedFrom(item);
            if (!smeltIn.isEmpty()) {
                need(smeltIn.getFirst(), n, depth + 1, stack, false);
                smelted += n;
                add(Kind.SMELT, Inv.id(smeltIn.getFirst()) + " -> " + Inv.id(item), n, null);
                return;
            }
            String mob = Sources.mobDrop(item);
            if (mob != null) {
                add(Kind.HUNT, Inv.id(item), n, mob);
                return;
            }
            if (!recipes.isEmpty()) {
                craft(item, pickRecipe(recipes, n, depth, stack), n, depth, stack, top);
                return;
            }
            Sources.Mined m = Sources.mined(item);
            if (m != null) {
                mine(item, n, m, depth, stack);
                return;
            }
            add(Kind.UNKNOWN, Inv.id(item), n, null);
        } finally {
            stack.remove(item);
        }
    }

    /** Explicit rows like obsidian or ancient_debris that are mined as themselves. */
    private static boolean isExplicitSelfMined(Item item) {
        String id = Inv.id(item);
        return id.equals("obsidian") || id.equals("ancient_debris") || id.endsWith("_log") || id.equals("sand") || id.equals("gravel")
                || id.equals("dirt") || id.equals("netherrack") || id.equals("soul_sand") || id.equals("end_stone");
    }

    private void mine(Item item, int n, Sources.Mined m, int depth, Set<Item> stack) {
        int blocks = (int) Math.ceil(n / m.perBlock());
        Block first = m.blocks().iterator().next();
        BlockState state = first.defaultBlockState();
        Item tool = ensureTool(state, depth, stack);
        String id = Inv.id(item);
        // Any log will do for planks/sticks/tables; asking Baritone for one species sends it far away.
        String what = id.endsWith("_log") ? "log" : id;
        StringBuilder extra = new StringBuilder();
        if (state.requiresCorrectToolForDrops()) extra.append(tool != null ? "with " + Inv.id(tool) : "needs " + Inv.minimalTool(state));
        if (m.note() != null) extra.append(extra.isEmpty() ? "" : "; ").append(m.note());
        add(Kind.MINE, what, n, extra.isEmpty() ? null : extra.toString());
        if (tools.contains(tool)) actions.getLast().tool = tool;
        int extraItems = (int) Math.floor(blocks * m.perBlock()) - n;
        if (extraItems > 0) sim.merge(item, extraItems, Integer::sum);
    }

    /** The tool that will harvest the block: one already there, or one planned (and crafted first) now. Null when none is needed or known. */
    private Item ensureTool(BlockState state, int depth, Set<Item> stack) {
        if (!state.requiresCorrectToolForDrops()) return null;
        for (Item t : tools) if (new ItemStack(t).isCorrectToolForDrops(state)) return t;
        for (var e : sim.entrySet()) {
            if (e.getValue() > 0 && new ItemStack(e.getKey()).isCorrectToolForDrops(state)) return e.getKey();
        }
        String min = Inv.minimalTool(state);
        Item tool = min == null ? null : Inv.item(min);
        if (tool == null) return null;
        // Counted as available only once it is planned - otherwise its own ingredients would "use" it.
        need(tool, 1, depth + 1, stack, false);
        tools.add(tool);
        return tool;
    }

    private void craft(Item item, Crafting.Recipe r, int n, int depth, Set<Item> stack, boolean top) {
        int times = (n + r.resultCount() - 1) / r.resultCount();
        for (Ingredient ing : r.ingredients()) needIngredient(ing, times, depth + 1, stack);
        if (!r.fits2x2()) needsTable = true;
        int extra = times * r.resultCount() - n;
        if (extra > 0) sim.merge(item, extra, Integer::sum);
        // Intermediates (planks, sticks...) are made by the craft tool on its own; list what the model should ask for.
        String id = Inv.id(item);
        boolean show = top || goalItems.contains(item) || id.equals("crafting_table") || id.equals("furnace") || id.equals("bucket")
                || id.endsWith("_pickaxe") || id.endsWith("_shovel") || id.endsWith("_axe") || id.endsWith("_sword") || id.equals("shears");
        if (show) add(Kind.CRAFT, id, n, r.fits2x2() ? null : "3x3");
    }

    private void needIngredient(Ingredient ing, int n, int depth, Set<Item> stack) {
        for (var e : sim.entrySet()) {
            if (n <= 0) return;
            if (e.getValue() > 0 && ing.test(new ItemStack(e.getKey()))) {
                int t = Math.min(e.getValue(), n);
                e.setValue(e.getValue() - t);
                used.merge(Inv.id(e.getKey()), t, Integer::sum);
                n -= t;
            }
        }
        if (n <= 0) return;
        need(pickOption(ing), n, depth, stack, false);
    }

    /** Among "any planks"-style options: the kind whose source is already there, else the first. */
    private Item pickOption(Ingredient ing) {
        List<Holder<Item>> opts = ing.items().toList();
        Item best = opts.getFirst().value();
        int bestScore = -1;
        for (Holder<Item> h : opts) {
            Item o = h.value();
            int score = 0;
            for (Crafting.Recipe r : Crafting.recipesFor(o)) {
                for (Ingredient sub : r.ingredients()) {
                    for (var e : sim.entrySet()) if (e.getValue() > 0 && sub.test(new ItemStack(e.getKey()))) score = Math.max(score, 2);
                }
            }
            if (Inv.id(o).startsWith("oak_")) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return best;
    }

    /** A recipe whose ingredients are all in the inventory already (e.g. iron_ingot from an iron_block). */
    private Crafting.Recipe readyRecipe(List<Crafting.Recipe> recipes, int n) {
        for (Crafting.Recipe r : recipes) {
            int times = (n + r.resultCount() - 1) / r.resultCount();
            Map<Item, Integer> left = new HashMap<>(sim);
            boolean ok = true;
            for (Ingredient ing : r.ingredients()) {
                int k = times;
                for (var e : left.entrySet()) {
                    if (k <= 0) break;
                    if (e.getValue() > 0 && ing.test(new ItemStack(e.getKey()))) {
                        int t = Math.min(e.getValue(), k);
                        e.setValue(e.getValue() - t);
                        k -= t;
                    }
                }
                if (k > 0) {
                    ok = false;
                    break;
                }
            }
            if (ok) return r;
        }
        return null;
    }

    /** The first recipe that does not run into unknown materials (tried on a copy of the state). */
    private Crafting.Recipe pickRecipe(List<Crafting.Recipe> recipes, int n, int depth, Set<Item> stack) {
        for (Crafting.Recipe r : recipes) {
            Planning trial = copy();
            trial.craft(r.result(), r, n, depth, new HashSet<>(stack), false);
            if (trial.actions.stream().noneMatch(a -> a.kind == Kind.UNKNOWN)) return r;
        }
        return recipes.getFirst();
    }

    private Planning copy() {
        Planning c = new Planning();
        c.sim.putAll(sim);
        c.goalItems.addAll(goalItems);
        c.tools.addAll(tools);
        return c;
    }

    private void add(Kind kind, String what, int count, String extra) {
        if (!actions.isEmpty()) {
            Action last = actions.getLast();
            if (last.kind == kind && last.what.equals(what)) {
                last.count += count;
                return;
            }
        }
        actions.add(new Action(kind, what, count, extra));
    }

    /** Furnace fuel: what the inventory can burn after the plan, and coal to mine for the rest. */
    private void planFuel() {
        if (smelted <= 0) return;
        var level = Minecraft.getInstance().level;
        int burnTicks = 0;
        for (var e : sim.entrySet()) {
            if (e.getValue() <= 0 || !Sources.isCheapFuel(e.getKey())) continue;
            burnTicks += level.fuelValues().burnDuration(new ItemStack(e.getKey())) * e.getValue();
        }
        int coverable = burnTicks / 200;
        if (coverable >= smelted) return;
        int coal = (int) Math.ceil((smelted - coverable) / 8.0);
        int at = actions.size();
        for (int i = 0; i < actions.size(); i++) if (actions.get(i).kind == Kind.SMELT) { at = i; break; }
        List<Action> tail = new ArrayList<>(actions.subList(at, actions.size()));
        while (actions.size() > at) actions.removeLast();
        need(Items.COAL, coal, 0, new HashSet<>(), false);
        Action last = actions.isEmpty() ? null : actions.getLast();
        if (last != null && last.kind == Kind.MINE && last.what.equals("coal")) last.extra = (last.extra == null ? "" : last.extra + "; ") + "furnace fuel (1 coal smelts 8; logs/planks work too)";
        actions.addAll(tail);
    }

    /**
     * Gather in bulk: every mine step moves up to right after the craft of the tool it needs (or to the
     * start), merging with an earlier step for the same block - one "mine 7 log" instead of three small trips.
     */
    private void batchMining() {
        List<Action> out = new ArrayList<>();
        for (Action a : actions) {
            if (a.kind != Kind.MINE) {
                out.add(a);
                continue;
            }
            int min = 0;
            if (a.tool != null) {
                String toolId = Inv.id(a.tool);
                for (int i = out.size() - 1; i >= 0; i--) {
                    if (out.get(i).kind == Kind.CRAFT && out.get(i).what.equals(toolId)) {
                        min = i + 1;
                        break;
                    }
                }
            }
            Action same = null;
            for (int i = min; i < out.size(); i++) {
                if (out.get(i).kind == Kind.MINE && out.get(i).what.equals(a.what)) {
                    same = out.get(i);
                    break;
                }
            }
            if (same != null) same.count += a.count;
            else out.add(min, a);
        }
        actions.clear();
        actions.addAll(out);
    }

    private String render(List<Goal> goals) {
        StringBuilder sb = new StringBuilder("Plan for ");
        List<String> g = new ArrayList<>();
        for (Goal goal : goals) g.add(goal.count + " " + Inv.id(goal.item));
        sb.append(String.join(" + ", g)).append(":\n");
        if (actions.isEmpty()) {
            sb.append("Everything is already in the inventory");
            return sb.append(needsTable ? " (craft needs a crafting table - it is placed and used automatically)." : ".").toString();
        }
        int i = 1;
        List<String> unknown = new ArrayList<>();
        for (Action a : actions) {
            String line = switch (a.kind) {
                case MINE -> "mine " + a.count + " " + a.what;
                case SMELT -> "smelt " + a.count + " " + a.what;
                case CRAFT -> "craft " + a.count + " " + a.what;
                case HUNT -> "get " + a.count + " " + a.what + " from " + a.extra;
                case UNKNOWN -> {
                    unknown.add(a.count + " " + a.what);
                    yield "obtain " + a.count + " " + a.what + " (no known recipe or source - find, loot or trade)";
                }
            };
            sb.append(i++).append(". ").append(line);
            if (a.extra != null && a.kind != Kind.HUNT) sb.append("  [").append(a.extra).append(']');
            sb.append('\n');
        }
        if (!used.isEmpty()) {
            List<String> u = new ArrayList<>();
            used.forEach((k, v) -> u.add(v + " " + k));
            sb.append("Uses from the inventory: ").append(String.join(", ", u)).append('\n');
        }
        if (actions.stream().anyMatch(a -> a.kind == Kind.MINE)) sb.append("Each mine step is the total for the whole plan - do it in one call, with ~20% spare.\n");
        if (smelted > 0 && Inv.count(Items.FURNACE) <= 0 && Inv.findInReach(Set.of("furnace", "smoker", "blast_furnace")) == null) {
            sb.append("Smelting needs a furnace: 8 cobblestone (smelt places it from the inventory; pick it up again to reuse).\n");
        }
        if (needsTable && Inv.count(Items.CRAFTING_TABLE) <= 0) sb.append("3x3 recipes need a crafting table - craft makes and places one; keep one in the inventory.\n");
        if (!unknown.isEmpty()) sb.append("Not plannable: ").append(String.join(", ", unknown)).append(".\n");
        sb.append("Craft calls make planks/sticks and other intermediates on their own.");
        return sb.toString().trim();
    }

    /** "10 obsidian, 1 flint_and_steel" / "nether_portal" / "3 iron_ingot" -> goals; unknown names are reported in {@code errors}. */
    public static List<Goal> parse(String text, List<String> errors) {
        List<Goal> out = new ArrayList<>();
        for (String part : text.toLowerCase(Locale.ROOT).split("\\s*[,;+]\\s*|\\s+and\\s+")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int count = 1;
            String[] w = p.split("\\s+", 2);
            if (w.length == 2 && w[0].matches("\\d+x?")) {
                count = Integer.parseInt(w[0].replace("x", ""));
                p = w[1].trim();
            } else if (w.length == 2 && w[1].matches("x?\\d+")) {
                count = Integer.parseInt(w[1].replace("x", ""));
                p = w[0].trim();
            }
            p = p.replace(' ', '_').replaceFirst("^minecraft:", "");
            List<String> composite = COMPOSITES.get(p);
            if (composite != null) {
                for (int k = 0; k < count; k++) out.addAll(parse(String.join(", ", composite), errors));
                continue;
            }
            Item item = Inv.item(p);
            if (item == null || item == Items.AIR) errors.add("unknown item '" + p + "'");
            else out.add(new Goal(item, Math.max(1, Math.min(4096, count))));
        }
        // Merge repeats (e.g. two composites asking for obsidian).
        Map<Item, Integer> merged = new LinkedHashMap<>();
        for (Goal goal : out) merged.merge(goal.item, goal.count, Integer::sum);
        List<Goal> result = new ArrayList<>();
        merged.forEach((k, v) -> result.add(new Goal(k, v)));
        return result;
    }
}
