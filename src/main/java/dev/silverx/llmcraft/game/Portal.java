package dev.silverx.llmcraft.game;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds and lights a nether portal next to the player without moving: a 4 wide x 5 tall frame, the
 * 10 obsidian in the frame proper and any filler block in the 4 corners (they hold up the top row).
 */
public final class Portal {
    private Portal() {}

    /** Bottom-left corner of the frame and the direction the frame extends in. */
    public record Site(BlockPos base, Direction along) {}

    private static final List<Item> FILLERS = List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.NETHERRACK, Items.ANDESITE,
            Items.DIORITE, Items.GRANITE, Items.TUFF, Items.STONE, Items.DEEPSLATE, Items.BLACKSTONE, Items.SANDSTONE, Items.OAK_PLANKS,
            Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS);

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /** Checks materials; null when ready, else what is missing. */
    public static String missing() {
        List<String> m = new ArrayList<>();
        int obs = Inv.count(Items.OBSIDIAN);
        if (obs < 10) m.add((10 - obs) + " more obsidian (mine obsidian, needs a diamond pickaxe)");
        if (Inv.count(Items.FLINT_AND_STEEL) <= 0) m.add("a flint_and_steel (craft: 1 iron_ingot + 1 flint)");
        if (obs >= 10 && filler() == null) m.add("4 blocks for the corners (cobblestone, dirt...) or 4 extra obsidian");
        return m.isEmpty() ? null : String.join(", ", m);
    }

    /** Corner block item: a cheap full block (4 needed), else obsidian when there are 14. */
    public static Item filler() {
        for (Item f : FILLERS) if (Inv.count(f) >= 4) return f;
        return Inv.count(Items.OBSIDIAN) >= 14 ? Items.OBSIDIAN : null;
    }

    /** A spot next to the player where the whole frame fits, stands on solid ground and is in reach. */
    public static Site findSite() {
        var p = mc().player;
        BlockPos feet = p.blockPosition();
        List<Direction> dirs = new ArrayList<>();
        dirs.add(p.getDirection());
        for (Direction d : Direction.Plane.HORIZONTAL) if (!dirs.contains(d)) dirs.add(d);
        for (int dist : new int[]{2, 3, 1})
            for (Direction facing : dirs) {
                Direction along = facing.getClockWise();
                for (int lateral : new int[]{-1, -2, 0, -3})
                    for (int dy : new int[]{0, -1, 1}) {
                        BlockPos base = feet.relative(facing, dist).relative(along, lateral).above(dy);
                        if (fits(base, along)) return new Site(base, along);
                    }
            }
        return null;
    }

    private static boolean fits(BlockPos base, Direction along) {
        var p = mc().player;
        var level = mc().level;
        double reach = p.blockInteractionRange() + 0.5; // the server allows +1
        for (int i = 0; i < 4; i++) {
            BlockPos ground = base.relative(along, i).below();
            if (!level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP) || Inv.opensOnClick(ground)) return false;
            for (int j = 0; j < 5; j++) {
                BlockPos pos = base.relative(along, i).above(j);
                if (!level.getBlockState(pos).canBeReplaced() || !level.getFluidState(pos).isEmpty()) return false;
                if (p.getBoundingBox().intersects(new AABB(pos))) return false;
                boolean frame = i == 0 || i == 3 || j == 0 || j == 4;
                if (frame && p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > reach) return false;
            }
        }
        return true;
    }

    private record Place(BlockPos pos, BlockPos support, Direction face, boolean obsidian) {}

    /** Placement order: bottom row on the ground, pillars upwards, top corners, then the top row from the sides. */
    private static List<Place> order(Site s) {
        BlockPos b = s.base;
        Direction a = s.along;
        List<Place> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            BlockPos pos = b.relative(a, i);
            out.add(new Place(pos, pos.below(), Direction.UP, i == 1 || i == 2));
        }
        for (int i : new int[]{0, 3}) {
            for (int j = 1; j <= 4; j++) {
                BlockPos pos = b.relative(a, i).above(j);
                out.add(new Place(pos, pos.below(), Direction.UP, j < 4));
            }
        }
        BlockPos top1 = b.relative(a, 1).above(4);
        BlockPos top2 = b.relative(a, 2).above(4);
        out.add(new Place(top1, top1.relative(a.getOpposite()), a, true));
        out.add(new Place(top2, top2.relative(a), a.getOpposite(), true));
        return out;
    }

    /** The whole job as a script: place 14 blocks, then light the inside with flint and steel. */
    public static Script buildTask(Site site, Item filler) {
        Script s = new Script("building a nether portal at " + site.base.toShortString());
        if (BaritoneBridge.isBusy()) s.then(BaritoneBridge::cancel);
        for (Place pl : order(site)) {
            Item item = pl.obsidian ? Items.OBSIDIAN : filler;
            boolean[] clicked = {false};
            s.then("placing " + Inv.id(item) + " at " + pl.pos.toShortString(), 30, mc -> {
                var level = mc.level;
                var st = level.getBlockState(pl.pos);
                if (st.is(Blocks.OBSIDIAN) || !pl.obsidian && !st.canBeReplaced()) return null;
                if (clicked[0]) return Script.WAIT;
                if (!st.canBeReplaced()) return "something is in the way at " + pl.pos.toShortString();
                String e = Inv.toHand(Inv.id(item));
                if (e != null) return e;
                Vec3 hit = Vec3.atCenterOf(pl.support).add(Vec3.atLowerCornerOf(pl.face.getUnitVec3i()).scale(0.5));
                Inv.faceInstantly(hit);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, pl.face, pl.support, false));
                mc.player.swing(InteractionHand.MAIN_HAND);
                clicked[0] = true;
                return Script.WAIT;
            });
        }
        BlockPos floor = site.base.relative(site.along, 1);
        BlockPos inside = floor.above();
        boolean[] lit = {false};
        s.then("lighting the portal", 40, mc -> {
            if (mc.level.getBlockState(inside).is(Blocks.NETHER_PORTAL)) return null;
            if (lit[0]) return Script.WAIT;
            String e = Inv.toHand("flint_and_steel");
            if (e != null) return e;
            Vec3 hit = new Vec3(floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5);
            Inv.faceInstantly(hit);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, floor, false));
            mc.player.swing(InteractionHand.MAIN_HAND);
            lit[0] = true;
            return Script.WAIT;
        });
        s.then(() -> s.note("nether portal built and lit at " + site.base.toShortString() + " - walk into the purple blocks at "
                + inside.toShortString() + " and wait ~4 s to travel"));
        return s;
    }
}
