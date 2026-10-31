package dev.silverx.llmcraft.game;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where raw materials come from: the block to mine for an item (diamond -> diamond_ore), what an item is
 * smelted from, and what is only dropped by mobs. Shared by the mine tool and the plan tool.
 */
public final class Sources {
    private Sources() {}

    /** Mine {@code blocks} for an item; {@code perBlock} is the average number of items one block drops. */
    public record Mined(Set<Block> blocks, double perBlock, String note) {}

    private record Row(String block, double perBlock, String note) {}

    private static final Map<String, Row> MINED = new LinkedHashMap<>();
    private static final Map<String, String> MOB_DROPS = new HashMap<>();
    /** Fallback smelting table for multiplayer, where the client does not know the server's recipes. */
    private static final Map<String, String> SMELTED_FROM = new HashMap<>();
    /** Block items whose block does not drop itself (needs silk touch or drops something else). */
    private static final Set<String> NOT_SELF_DROPPING = Set.of("stone", "deepslate", "grass_block", "mycelium", "glowstone", "bookshelf", "clay",
            "snow_block", "melon", "sea_lantern", "amethyst_cluster", "budding_amethyst", "cobweb", "ice", "blue_ice", "packed_ice", "glass",
            "glass_pane", "gilded_blackstone", "turtle_egg", "reinforced_deepslate", "bedrock", "spawner", "farmland", "dirt_path");

    static {
        MINED.put("diamond", new Row("diamond_ore", 1, null));
        MINED.put("raw_iron", new Row("iron_ore", 1, null));
        MINED.put("raw_gold", new Row("gold_ore", 1, null));
        MINED.put("raw_copper", new Row("copper_ore", 3, null));
        MINED.put("coal", new Row("coal_ore", 1, null));
        MINED.put("redstone", new Row("redstone_ore", 4, null));
        MINED.put("lapis_lazuli", new Row("lapis_ore", 5, null));
        MINED.put("emerald", new Row("emerald_ore", 1, null));
        MINED.put("quartz", new Row("nether_quartz_ore", 1, "Nether only"));
        MINED.put("gold_nugget", new Row("nether_gold_ore", 3, "Nether only"));
        MINED.put("flint", new Row("gravel", 0.1, "random drop: about 1 flint per 10 gravel"));
        MINED.put("cobblestone", new Row("stone", 1, null));
        MINED.put("cobbled_deepslate", new Row("deepslate", 1, null));
        MINED.put("clay_ball", new Row("clay", 4, null));
        MINED.put("snowball", new Row("snow_block", 4, null));
        MINED.put("glowstone_dust", new Row("glowstone", 3, "Nether only"));
        MINED.put("amethyst_shard", new Row("amethyst_cluster", 4, null));
        MINED.put("string", new Row("cobweb", 1, "needs a sword; spiders also drop it"));
        MINED.put("wheat_seeds", new Row("short_grass", 0.12, "random drop from grass"));
        MINED.put("dirt", new Row("dirt grass_block", 1, null));
        MINED.put("obsidian", new Row("obsidian", 1, "needs a diamond pickaxe; about 9 s per block. Baritone finds it (lava lakes, ruined portals)"));
        MINED.put("ancient_debris", new Row("ancient_debris", 1, "Nether only, Y 8-22"));

        MOB_DROPS.put("leather", "cows (kill them)");
        MOB_DROPS.put("beef", "cows");
        MOB_DROPS.put("porkchop", "pigs");
        MOB_DROPS.put("mutton", "sheep");
        MOB_DROPS.put("chicken", "chickens");
        MOB_DROPS.put("rabbit", "rabbits");
        MOB_DROPS.put("feather", "chickens");
        MOB_DROPS.put("white_wool", "sheep (or craft from 4 string)");
        MOB_DROPS.put("egg", "chickens lay them");
        MOB_DROPS.put("bone", "skeletons");
        MOB_DROPS.put("arrow", "skeletons");
        MOB_DROPS.put("gunpowder", "creepers");
        MOB_DROPS.put("spider_eye", "spiders");
        MOB_DROPS.put("rotten_flesh", "zombies");
        MOB_DROPS.put("slime_ball", "slimes");
        MOB_DROPS.put("ender_pearl", "endermen (or trade with clerics/piglins)");
        MOB_DROPS.put("blaze_rod", "blazes in Nether fortresses");
        MOB_DROPS.put("ghast_tear", "ghasts in the Nether");
        MOB_DROPS.put("ink_sac", "squid");
        MOB_DROPS.put("phantom_membrane", "phantoms");

        SMELTED_FROM.put("iron_ingot", "raw_iron");
        SMELTED_FROM.put("gold_ingot", "raw_gold");
        SMELTED_FROM.put("copper_ingot", "raw_copper");
        SMELTED_FROM.put("netherite_scrap", "ancient_debris");
        SMELTED_FROM.put("glass", "sand");
        SMELTED_FROM.put("stone", "cobblestone");
        SMELTED_FROM.put("smooth_stone", "stone");
        SMELTED_FROM.put("deepslate", "cobbled_deepslate");
        SMELTED_FROM.put("charcoal", "oak_log");
        SMELTED_FROM.put("brick", "clay_ball");
        SMELTED_FROM.put("terracotta", "clay");
        SMELTED_FROM.put("dried_kelp", "kelp");
        SMELTED_FROM.put("green_dye", "cactus");
        SMELTED_FROM.put("cooked_beef", "beef");
        SMELTED_FROM.put("cooked_porkchop", "porkchop");
        SMELTED_FROM.put("cooked_mutton", "mutton");
        SMELTED_FROM.put("cooked_chicken", "chicken");
        SMELTED_FROM.put("cooked_rabbit", "rabbit");
        SMELTED_FROM.put("cooked_cod", "cod");
        SMELTED_FROM.put("cooked_salmon", "salmon");
        SMELTED_FROM.put("baked_potato", "potato");
    }

    /**
     * Blocks to mine for an item, or null when the item does not come from a block. Ores map to their
     * ore blocks (incl. deepslate variants); most block items map to their own block.
     */
    public static Mined mined(Item item) {
        String id = Inv.id(item);
        Row row = MINED.get(id);
        if (row != null) return new Mined(Mining.resolve(row.block), row.perBlock, row.note);
        if (id.endsWith("_log") || id.endsWith("_stem")) return new Mined(Mining.resolve(id), 1, null);
        if (item instanceof BlockItem bi && !NOT_SELF_DROPPING.contains(id) && !id.endsWith("_ore") && !id.endsWith("_leaves")
                && !id.contains("glass") && !id.startsWith("infested_") && !id.contains("shulker_box")) {
            Block b = bi.getBlock();
            return new Mined(Set.of(b), 1, null);
        }
        return null;
    }

    /** "diamond" -> the ore blocks; only for item names that are not block ids themselves. Null if it is not such an alias. */
    public static Mined alias(String query) {
        String q = query.trim().toLowerCase(java.util.Locale.ROOT).replaceFirst("^minecraft:", "");
        if (q.contains(" ") || q.contains(",")) return null;
        Row row = MINED.get(q);
        if (row == null && q.endsWith("s")) row = MINED.get(q = q.substring(0, q.length() - 1)); // "diamonds"
        if (row == null || row.block.equals(q)) return null;
        return new Mined(Mining.resolve(row.block), row.perBlock, row.note);
    }

    /** Fuel smelting may burn without being told: coal-likes, logs, planks, sticks - never crafted things like tables, chests or boats. */
    public static boolean isCheapFuel(Item item) {
        String id = Inv.id(item);
        return id.equals("coal") || id.equals("charcoal") || id.equals("coal_block") || id.equals("blaze_rod") || id.equals("dried_kelp_block")
                || id.equals("stick") || id.equals("bamboo") || id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_planks")
                || id.endsWith("_stem") || id.endsWith("_hyphae") || id.endsWith("_sapling");
    }

    /** The item an {@link #alias} query counts ("diamonds" -> diamond). */
    public static Item aliasItem(String query) {
        String q = query.trim().toLowerCase(java.util.Locale.ROOT).replaceFirst("^minecraft:", "");
        if (!MINED.containsKey(q) && q.endsWith("s")) q = q.substring(0, q.length() - 1);
        return Inv.item(q);
    }

    public static String mobDrop(Item item) {
        return MOB_DROPS.get(Inv.id(item));
    }

    /** Inputs that smelt into the item; ones already in the inventory first. Empty if it is not smelted. */
    public static List<Item> smeltedFrom(Item out) {
        List<Item> inputs = new ArrayList<>();
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server != null && mc.level != null) {
            var ctx = SlotDisplayContext.fromLevel(mc.level);
            for (var holder : server.getRecipeManager().getRecipes()) {
                if (!(holder.value() instanceof SmeltingRecipe r)) continue;
                boolean match = false;
                for (var d : r.display()) {
                    if (d instanceof FurnaceRecipeDisplay f && f.result().resolveForFirstStack(ctx).is(out)) match = true;
                }
                if (!match) continue;
                for (Holder<Item> h : r.input().items().toList()) if (!inputs.contains(h.value())) inputs.add(h.value());
            }
        } else {
            String from = SMELTED_FROM.get(Inv.id(out));
            Item in = from == null ? null : Inv.item(from);
            if (in != null) inputs.add(in);
        }
        // Prefer what is already there, then raw ores over ore blocks (silk touch) and logs over wood.
        inputs.sort((a, b) -> Integer.compare(score(b), score(a)));
        return inputs;
    }

    private static int score(Item i) {
        String id = Inv.id(i);
        int s = Inv.count(i) > 0 ? 100 : 0;
        if (id.startsWith("raw_")) s += 10;
        if (id.endsWith("_ore")) s -= 10;
        if (id.equals("oak_log")) s += 5;
        if (id.contains("stripped") || id.endsWith("_wood")) s -= 5;
        return s;
    }
}
