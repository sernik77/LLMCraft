package dev.silverx.llmcraft.game;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Being stuck: a progress tracker for movement tasks (moved / broke a block / picked something up / is
 * digging / Baritone is still calculating), a diagnosis of the player's surroundings, and quick ways out.
 */
public final class Stuck {
    private Stuck() {}

    /** Seconds without any progress after which a movement task gives up. */
    public static final int NO_PROGRESS_SECONDS = 8;

    /** Tracks whether anything meaningful changed since the last progress. */
    public static final class Progress {
        private Vec3 anchor;
        private int broken;
        private int items;
        private long at = System.currentTimeMillis();

        public Progress(Minecraft mc) {
            reset(mc);
        }

        private void reset(Minecraft mc) {
            anchor = mc.player.position();
            broken = Mining.totalBroken();
            items = Inv.totalCount();
            at = System.currentTimeMillis();
        }

        /** Updates the tracker; returns seconds since the last progress. */
        public double idleSeconds(Minecraft mc) {
            boolean moved = mc.player.position().distanceTo(anchor) >= 1.0;
            boolean dug = Mining.totalBroken() != broken || mc.gameMode != null && mc.gameMode.isDestroying();
            boolean got = Inv.totalCount() != items;
            boolean thinking = BaritoneBridge.isCalculating();
            if (moved || dug || got || thinking) reset(mc);
            return (System.currentTimeMillis() - at) / 1000.0;
        }
    }

    /** What holds the player in place, or null when they can move freely. */
    public static String diagnose(Minecraft mc) {
        var p = mc.player;
        var level = mc.level;
        BlockPos feet = p.blockPosition();
        List<String> out = new ArrayList<>();
        if (p.isInLava()) out.add("in lava");
        else if (p.isUnderWater()) out.add("under water");
        else if (p.isInWater()) out.add("in water");
        var atFeet = level.getBlockState(feet);
        if (atFeet.is(Blocks.COBWEB)) out.add("in a cobweb");
        if (atFeet.is(Blocks.POWDER_SNOW)) out.add("in powder snow");
        if (atFeet.is(Blocks.SWEET_BERRY_BUSH)) out.add("in a berry bush");
        if (p.isInWall()) out.add("inside a block (suffocating)");
        int walls = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) if (blocked(feet.relative(d)) || blocked(feet.relative(d).above())) walls++;
        boolean covered = blocked(feet.above(2));
        int open = 0;
        while (open < 6 && !blocked(feet.above(2 + open))) open++;
        if (walls == 4 && covered) out.add("enclosed on all sides with a block above (buried)");
        else if (walls == 4) out.add("in a 1x1 hole/shaft (walls on all 4 sides, " + (open >= 6 ? "open sky above" : open + " free blocks above") + ")");
        else if (walls == 3) out.add("in a dead end (only one side open)");
        if (p.verticalCollision && !p.onGround() && covered) out.add("hitting the ceiling");
        return out.isEmpty() ? null : String.join(", ", out);
    }

    /** Only the diagnoses that really hold the player (for the status line); dead ends and swimming are normal. */
    public static String trapped(Minecraft mc) {
        String d = diagnose(mc);
        if (d == null) return null;
        List<String> keep = new ArrayList<>();
        for (String part : d.split(", ")) {
            if (part.contains("buried") || part.contains("cobweb") || part.contains("inside a block") || part.contains("powder snow")
                    || part.contains("hole") && !part.contains("open sky")) keep.add(part);
        }
        return keep.isEmpty() ? null : String.join(", ", keep);
    }

    private static boolean blocked(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** An open spot 2..8 blocks away the player could stand on (air at feet and head, solid ground), nearest first. */
    public static BlockPos openSpot(Minecraft mc) {
        var level = mc.level;
        BlockPos feet = mc.player.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-8, -3, -8), feet.offset(8, 6, 8))) {
            double d = pos.distSqr(feet);
            if (d < 4 || d >= bestD) continue;
            if (blocked(pos) || blocked(pos.above()) || !blocked(pos.below())) continue;
            if (!level.getFluidState(pos).isEmpty() || level.getBlockState(pos.below()).is(Blocks.LAVA) || level.getBlockState(pos.below()).is(Blocks.MAGMA_BLOCK)) continue;
            int sides = 0;
            for (Direction dir : Direction.Plane.HORIZONTAL) if (!blocked(pos.relative(dir)) && !blocked(pos.relative(dir).above())) sides++;
            if (sides < 2) continue; // not another hole
            bestD = d;
            best = pos.immutable();
        }
        return best;
    }

    /** Pillar up out of a hole: jump and place a throwaway block under the feet, `height` times. */
    public static Controller.Task pillarUp(int height) {
        return new Controller.Task() {
            int placed;
            int age;
            double startY = Double.NaN;

            @Override
            public String tick(Minecraft mc) {
                var p = mc.player;
                if (Double.isNaN(startY)) {
                    if (!p.onGround()) return null; // measure from the ground, not mid-air
                    startY = p.getY();
                }
                if (++age > 20 * 6) return Script.FAILED + "pillaring timed out after " + placed + " block(s)";
                if (placed >= height && p.onGround()) return "pillared up " + placed + " block(s)";
                int slot = throwaway();
                if (slot < 0) return Script.FAILED + "no throwaway blocks (cobblestone, dirt...) to pillar up with";
                if (blocked(p.blockPosition().above(2))) return Script.FAILED + "a block above the head stops pillaring - dig it first";
                Inv.toHand(slot);
                p.setXRot(90f);
                if (p.onGround()) Controller.hold(mc.options.keyJump, 2);
                BlockPos below = BlockPos.containing(p.getX(), p.getY() - 0.2, p.getZ());
                // feet must be above the block's top first, or the player's own hitbox blocks the placement
                if (!p.onGround() && p.getY() > startY + placed + 1.02 && !blocked(below) && blocked(below.below())) {
                    BlockPos support = below.below();
                    var hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                    p.swing(InteractionHand.MAIN_HAND);
                    placed++;
                }
                return null;
            }

            @Override
            public void onStop() {
                Controller.release(Minecraft.getInstance().options.keyJump);
            }

            @Override
            public String describe() {
                return "pillaring up";
            }
        };
    }

    /** Dig out sideways: break the two blocks in front (feet + head level) in the most open direction and walk in. */
    public static Controller.Task digOut() {
        Minecraft mc = Minecraft.getInstance();
        BlockPos feet = mc.player.blockPosition();
        Direction best = Direction.NORTH;
        int bestFree = -1;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int free = 0;
            for (int i = 1; i <= 4; i++) {
                if (!blocked(feet.relative(d, i))) free++;
                if (!blocked(feet.relative(d, i).above())) free++;
            }
            if (free > bestFree) {
                bestFree = free;
                best = d;
            }
        }
        Direction dir = best;
        List<BlockPos> toBreak = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            toBreak.add(feet.relative(dir, i).above());
            toBreak.add(feet.relative(dir, i));
        }
        return new Controller.Task() {
            Controller.Task sub;
            int idx;
            int age;

            @Override
            public String tick(Minecraft mc) {
                if (++age > 20 * 12) return Script.FAILED + "digging out timed out";
                if (sub != null) {
                    String r = sub.tick(mc);
                    if (r == null) return null;
                    sub.onStop();
                    sub = null;
                    if (idx > toBreak.size()) return r.startsWith("arrived") ? "dug out " + dir.getName() : Script.FAILED + "dug " + dir.getName() + " but " + r;
                }
                while (idx < toBreak.size() && !blocked(toBreak.get(idx))) idx++;
                if (idx < toBreak.size()) {
                    BlockPos pos = toBreak.get(idx++);
                    var st = mc.level.getBlockState(pos);
                    if (st.is(Blocks.BEDROCK) || st.getDestroySpeed(mc.level, pos) < 0) return Script.FAILED + "unbreakable block " + dir.getName();
                    if (!mc.level.getFluidState(pos.above()).isEmpty() || mc.level.getBlockState(pos.relative(dir)).is(Blocks.LAVA))
                        return Script.FAILED + "fluid behind the wall " + dir.getName() + " - not digging into it";
                    Inv.bestToolFor(st);
                    sub = Controller.mine(pos);
                    return null;
                }
                idx++;
                sub = Controller.walkTo(Vec3.atBottomCenterOf(feet.relative(dir, 2)), false, 0.35);
                return null;
            }

            @Override
            public void onStop() {
                if (sub != null) sub.onStop();
            }

            @Override
            public String describe() {
                return "digging out " + dir.getName();
            }
        };
    }

    /** Hotbar/inventory slot of a cheap full block to pillar or bridge with, or -1. */
    static int throwaway() {
        var inv = Minecraft.getInstance().player.getInventory();
        String[] prefer = {"cobblestone", "cobbled_deepslate", "dirt", "netherrack", "andesite", "diorite", "granite", "tuff", "stone", "deepslate", "gravel", "sand"};
        for (String id : prefer) {
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && Inv.id(s).equals(id)) return i;
            }
        }
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof BlockItem bi && bi.getBlock().defaultBlockState().isCollisionShapeFullBlock(Minecraft.getInstance().level, BlockPos.ZERO)
                    && !Inv.id(s).contains("ore") && !Inv.id(s).contains("obsidian") && !Inv.id(s).contains("diamond") && !Inv.id(s).contains("chest")
                    && !Inv.id(s).contains("table") && !Inv.id(s).contains("furnace")) return i;
        }
        return -1;
    }

    static String pos(Minecraft mc) {
        var p = mc.player;
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", p.getX(), p.getY(), p.getZ());
    }
}
