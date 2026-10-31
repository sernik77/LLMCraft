package dev.silverx.llmcraft.game;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Inventory work without any GUI: items are found by name and moved with the same container clicks the
 * vanilla screens send. With no container open the player's own menu (id 0, incl. the 2x2 grid) is used.
 * Call on the client thread.
 */
public final class Inv {
    private Inv() {}

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    // ---- item names ----

    public static String id(Item item) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    public static String id(ItemStack s) {
        return id(s.getItem());
    }

    /** Resolves "oak_planks", "minecraft:oak_planks" or "Oak Planks" to an item, or null. */
    public static Item item(String query) {
        if (query == null || query.isBlank()) return null;
        String q = query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        Identifier rl = Identifier.tryParse(q.contains(":") ? q : "minecraft:" + q);
        if (rl != null) {
            Optional<Item> it = BuiltInRegistries.ITEM.getOptional(rl);
            if (it.isPresent() && it.get() != Items.AIR) return it.get();
        }
        return null;
    }

    /** True when the stack matches the query: exact id, or (for loose queries) the id contains it. */
    public static boolean matches(ItemStack s, String query, boolean loose) {
        if (s.isEmpty() || query == null) return false;
        String q = query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (q.startsWith("minecraft:")) q = q.substring(10);
        String id = id(s);
        return id.equals(q) || loose && id.contains(q);
    }

    // ---- inventory ----

    /** Item id -> total count in the main inventory, armor and offhand. */
    public static Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        var p = mc().player;
        if (p == null) return out;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) out.merge(id(s), s.getCount(), Integer::sum);
        }
        ItemStack carried = p.containerMenu.getCarried();
        if (!carried.isEmpty()) out.merge(id(carried), carried.getCount(), Integer::sum);
        return out;
    }

    public static int count(Item item) {
        var p = mc().player;
        if (p == null) return 0;
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
        return n;
    }

    /** "+3 oak_log, -1 stick" between two {@link #counts()} snapshots, or "" when nothing changed. */
    public static String delta(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> d = new LinkedHashMap<>();
        after.forEach((k, v) -> d.merge(k, v, Integer::sum));
        before.forEach((k, v) -> d.merge(k, -v, Integer::sum));
        List<String> parts = new ArrayList<>();
        d.forEach((k, v) -> {
            if (v != 0) parts.add((v > 0 ? "+" : "") + v + " " + k);
        });
        return String.join(", ", parts);
    }

    /** Inventory index (0-8 hotbar, 9-35 main, 36-39 armor, 40 offhand) of the first matching stack, or -1. */
    public static int find(String query) {
        var p = mc().player;
        if (p == null) return -1;
        Inventory inv = p.getInventory();
        for (boolean loose : new boolean[]{false, true}) {
            // hotbar first: no click needed to use it
            for (int i = 0; i < 36; i++) if (matches(inv.getItem(i), query, loose)) return i;
            if (matches(inv.getItem(40), query, loose)) return 40;
        }
        return -1;
    }

    public static int find(Item item) {
        var p = mc().player;
        if (p == null) return -1;
        Inventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getItem(i).is(item)) return i;
        return inv.getItem(40).is(item) ? 40 : -1;
    }

    // ---- menu clicks ----

    /** The menu clicks go to: the open container, or the player's own inventory menu. */
    public static AbstractContainerMenu menu() {
        return mc().player.containerMenu;
    }

    /** Menu slot index that shows the given player inventory index, or -1. */
    public static int menuSlot(AbstractContainerMenu menu, int invIndex) {
        Inventory inv = mc().player.getInventory();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (s.container == inv && s.getContainerSlot() == invIndex) return i;
        }
        return -1;
    }

    public static void click(AbstractContainerMenu menu, int slot, int button, ClickType type) {
        mc().gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, mc().player);
    }

    /** True when the slot belongs to the player's inventory (not to the container/crafting grid). */
    public static boolean isPlayerSlot(Slot s) {
        return s.container == mc().player.getInventory();
    }

    /**
     * Puts a matching item into the main hand: selects it when it is on the hotbar, otherwise swaps it
     * into an empty (or the selected) hotbar slot. Returns an error message, or null on success.
     */
    public static String toHand(String query) {
        int idx = find(query);
        if (idx < 0) return "no '" + query + "' in the inventory";
        return toHand(idx);
    }

    public static String toHand(int idx) {
        var p = mc().player;
        Inventory inv = p.getInventory();
        if (idx >= 0 && idx <= 8) {
            inv.setSelectedSlot(idx);
            return null;
        }
        int target = inv.getSelectedSlot();
        if (!inv.getItem(target).isEmpty()) {
            for (int i = 0; i <= 8; i++) {
                if (inv.getItem(i).isEmpty()) {
                    target = i;
                    break;
                }
            }
        }
        AbstractContainerMenu menu = menu();
        int slot = menuSlot(menu, idx);
        if (slot < 0) return "that item is not reachable from the open container";
        click(menu, slot, target, ClickType.SWAP);
        inv.setSelectedSlot(target);
        return null;
    }

    /** Selects the inventory's best tool for breaking the block (does nothing if bare hands are as good). */
    public static String bestToolFor(BlockState state) {
        var p = mc().player;
        Inventory inv = p.getInventory();
        int best = -1;
        float bestSpeed = inv.getItem(inv.getSelectedSlot()).getDestroySpeed(state);
        boolean needsTool = state.requiresCorrectToolForDrops();
        boolean bestCorrect = !needsTool || inv.getItem(inv.getSelectedSlot()).isCorrectToolForDrops(state);
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            boolean correct = !needsTool || s.isCorrectToolForDrops(state);
            float speed = s.getDestroySpeed(state);
            if (correct && !bestCorrect || correct == bestCorrect && speed > bestSpeed + 0.01f) {
                best = i;
                bestSpeed = speed;
                bestCorrect = correct;
            }
        }
        if (best >= 0) toHand(best);
        return id(inv.getItem(inv.getSelectedSlot()));
    }

    /** Whether some inventory item can harvest the block (or it needs no special tool). */
    public static boolean canHarvest(BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return true;
        Inventory inv = mc().player.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getItem(i).isCorrectToolForDrops(state)) return true;
        return false;
    }

    /** Cheapest vanilla tool that can harvest the block, e.g. "stone_pickaxe", or null. */
    public static String minimalTool(BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return null;
        Item[][] tiers = {
                {Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE},
                {Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE},
                {Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL},
                {Items.SHEARS}, {Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE}, {Items.WOODEN_SWORD, Items.IRON_SWORD}};
        for (Item[] row : tiers) for (Item t : row) if (new ItemStack(t).isCorrectToolForDrops(state)) return id(t);
        return "a special tool";
    }

    // ---- world helpers shared by crafting/containers ----

    /** Nearest block within reach whose id is one of {@code ids}, or null. */
    public static BlockPos findInReach(java.util.Set<String> ids) {
        var p = mc().player;
        var level = mc().level;
        Vec3 eye = p.getEyePosition();
        double reach = p.blockInteractionRange();
        BlockPos c = p.blockPosition();
        int r = (int) Math.ceil(reach) + 1;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r + 1, r))) {
            String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
            if (!ids.contains(id)) continue;
            double d = eye.distanceTo(Vec3.atCenterOf(pos));
            if (d <= reach && d < bestD) {
                bestD = d;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** Right clicks a block (opens chests, tables, furnaces...). Turns the camera instantly first. */
    public static void useBlock(BlockPos pos) {
        var p = mc().player;
        Vec3 center = Vec3.atCenterOf(pos);
        Direction face = Direction.getApproximateNearest(p.getEyePosition().subtract(center));
        Vec3 hitVec = center.add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.5));
        faceInstantly(hitVec);
        if (p.isShiftKeyDown()) mc().options.keyShift.setDown(false);
        mc().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, face, pos, false));
        p.swing(InteractionHand.MAIN_HAND);
    }

    public static void faceInstantly(Vec3 target) {
        var p = mc().player;
        float[] yp = Controller.anglesTo(p.getEyePosition(), target);
        p.setYRot(yp[0]);
        p.setXRot(yp[1]);
    }

    /**
     * Steps that break a workstation an action placed (crafting table, furnace) and collect it again, so it
     * travels with the player to the next job. Never fails the owner: when it cannot, it notes where it stayed.
     */
    public static Script pickUpSteps(Script owner, BlockPos pos, Item item) {
        Script s = new Script("picking up " + id(item));
        int[] before = {0};
        Controller.Task[] sub = {null};
        boolean[] skipped = {false};
        s.then("breaking the " + id(item) + " at " + pos.toShortString(), 20 * 15, mc -> {
            var st = mc.level.getBlockState(pos);
            if (sub[0] == null) {
                before[0] = count(item);
                if (!st.getBlock().asItem().equals(item)) return null;
                if (!canHarvest(st)) {
                    owner.note("left the " + id(item) + " at " + pos.toShortString() + " (no tool that picks it up)");
                    skipped[0] = true;
                    return null;
                }
                bestToolFor(st);
                sub[0] = Controller.mine(pos);
            }
            String r = sub[0].tick(mc);
            if (r == null) return Script.WAIT;
            sub[0].onStop();
            return null;
        });
        s.then("collecting the " + id(item), 20 * 6, new Script.Step() {
            Controller.Task walk;
            int age;

            @Override
            public String tick(Minecraft mc) {
                if (skipped[0] || sub[0] == null) return null;
                if (count(item) > before[0]) {
                    if (walk != null) walk.onStop();
                    owner.note("picked the " + id(item) + " back up (it stays in the inventory for the next time)");
                    return null;
                }
                if (++age > 100) {
                    if (walk != null) walk.onStop();
                    owner.note("the " + id(item) + " dropped at " + pos.toShortString() + " but was not picked up");
                    return null;
                }
                if (age > 10 && walk == null) walk = Controller.walkTo(Vec3.atBottomCenterOf(pos), false, 0.3);
                if (walk != null && walk.tick(mc) != null) walk.onStop();
                return Script.WAIT;
            }
        });
        return s;
    }

    /** The item form of the block at a position (furnace, smoker...). */
    public static Item blockItemAt(BlockPos pos) {
        return mc().level.getBlockState(pos).getBlock().asItem();
    }

    /**
     * Whether right clicking the block does something instead of placing against it (crafting table, chest,
     * furnace, door, button...). Placement must never target these - it would open a screen or toggle them.
     */
    public static boolean opensOnClick(BlockPos pos) {
        var level = mc().level;
        var st = level.getBlockState(pos);
        if (st.hasBlockEntity() || st.getMenuProvider(level, pos) != null) return true;
        var b = st.getBlock();
        return b instanceof net.minecraft.world.level.block.DoorBlock || b instanceof net.minecraft.world.level.block.TrapDoorBlock
                || b instanceof net.minecraft.world.level.block.FenceGateBlock || b instanceof net.minecraft.world.level.block.ButtonBlock
                || b instanceof net.minecraft.world.level.block.LeverBlock || b instanceof net.minecraft.world.level.block.NoteBlock
                || b instanceof net.minecraft.world.level.block.RepeaterBlock || b instanceof net.minecraft.world.level.block.ComparatorBlock
                || b instanceof net.minecraft.world.level.block.CakeBlock || b instanceof net.minecraft.world.level.block.RespawnAnchorBlock;
    }

    /**
     * Places the given block item next to the player (on solid ground, not inside the player).
     * Returns the position it will appear at, or null when there is no spot or no item.
     */
    public static BlockPos placeNearby(Item item) {
        var p = mc().player;
        var level = mc().level;
        int idx = find(item);
        if (idx < 0) return null;
        BlockPos feet = p.blockPosition();
        double reach = p.blockInteractionRange();
        BlockPos chosen = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).canBeReplaced() || !level.getFluidState(pos).isEmpty()) continue;
                    BlockPos below = pos.below();
                    if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) || opensOnClick(below)) continue;
                    if (p.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos))) continue;
                    double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
                    if (d > reach - 0.3) continue;
                    // prefer spots at feet level, then nearest
                    double score = d + Math.abs(dy) * 2;
                    if (score < bestD) {
                        bestD = score;
                        chosen = pos.immutable();
                    }
                }
        if (chosen == null) return null;
        String err = toHand(idx);
        if (err != null) return null;
        BlockPos below = chosen.below();
        Vec3 hitVec = new Vec3(chosen.getX() + 0.5, chosen.getY(), chosen.getZ() + 0.5);
        faceInstantly(hitVec);
        if (p.isShiftKeyDown()) mc().options.keyShift.setDown(false);
        mc().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, Direction.UP, below, false));
        p.swing(InteractionHand.MAIN_HAND);
        return chosen;
    }
}
