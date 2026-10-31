package dev.silverx.llmcraft.game;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Gathering blocks: block name resolution, a broken-block counter, Baritone supervision and a no-Baritone fallback. */
public final class Mining {
    private Mining() {}

    private static final Map<Block, Integer> BROKEN = new HashMap<>();

    /** Fabric's client block-break event feeds this (works for Baritone and for the player). */
    public static synchronized void onBroken(BlockState state) {
        BROKEN.merge(state.getBlock(), 1, Integer::sum);
    }

    public static synchronized int broken(Set<Block> blocks) {
        int n = 0;
        for (Block b : blocks) n += BROKEN.getOrDefault(b, 0);
        return n;
    }

    /**
     * "oak_log" -> oak_log; "log"/"logs" -> every *_log; "diamond_ore" -> diamond_ore + deepslate_diamond_ore.
     * Several names may be separated by spaces or commas.
     */
    public static Set<Block> resolve(String query) {
        Set<Block> out = new LinkedHashSet<>();
        for (String raw : query.toLowerCase(Locale.ROOT).split("[\\s,]+")) {
            String q = raw.trim();
            if (q.isEmpty()) continue;
            if (q.startsWith("minecraft:")) q = q.substring(10);
            Block exact = block(q);
            if (exact != null) {
                out.add(exact);
                Block deep = block("deepslate_" + q);
                if (q.endsWith("_ore") && deep != null) out.add(deep);
                if (q.startsWith("deepslate_") && q.endsWith("_ore") && block(q.substring(10)) != null) out.add(block(q.substring(10)));
                continue;
            }
            String singular = q.endsWith("s") ? q.substring(0, q.length() - 1) : q;
            List<Block> suffix = new ArrayList<>();
            List<Block> contains = new ArrayList<>();
            for (Block b : BuiltInRegistries.BLOCK) {
                String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
                if (id.startsWith("stripped_") && !q.contains("stripped")) continue;
                if (id.startsWith("potted_") || id.startsWith("infested_")) continue;
                if (id.endsWith("_" + singular)) suffix.add(b);
                else if (id.contains(singular)) contains.add(b);
            }
            out.addAll(suffix.isEmpty() ? contains.subList(0, Math.min(12, contains.size())) : suffix);
        }
        out.remove(Blocks.AIR);
        return out;
    }

    private static Block block(String path) {
        Identifier id = Identifier.tryParse("minecraft:" + path);
        if (id == null) return null;
        return BuiltInRegistries.BLOCK.getOptional(id).filter(b -> b != Blocks.AIR).orElse(null);
    }

    public static String ids(Set<Block> blocks) {
        List<String> names = new ArrayList<>();
        for (Block b : blocks) names.add(BuiltInRegistries.BLOCK.getKey(b).getPath());
        return String.join(" ", names);
    }

    /**
     * Supervises a running Baritone process. Finishes when {@code done} holds (Baritone is then stopped),
     * when Baritone has been idle for 2 s, or fails when Baritone keeps reporting that it cannot path.
     */
    public static Controller.Task baritoneWatch(String label, BooleanSupplier done) {
        long started = System.currentTimeMillis();
        return new Controller.Task() {
            int age;
            int idle;

            @Override
            public String tick(Minecraft mc) {
                age++;
                if (done != null && done.getAsBoolean()) {
                    BaritoneBridge.cancel();
                    return "goal reached";
                }
                if (age < 20) return null;
                int noPath = 0;
                for (String l : ChatLog.since(started, "[Baritone]")) {
                    if (l.contains("Unable to find any path") || l.contains("No locations for") || l.contains("No goal")) noPath++;
                }
                if (noPath >= 3) {
                    BaritoneBridge.cancel();
                    return Script.FAILED + "Baritone cannot reach a target (it reported " + noPath + " path failures)";
                }
                idle = BaritoneBridge.isBusy() ? 0 : idle + 1;
                return idle >= 40 ? "Baritone stopped on its own" : null;
            }

            @Override
            public void onStop() {
                if (age > 0 && BaritoneBridge.isBusy()) BaritoneBridge.cancel();
            }

            @Override
            public String describe() {
                return label;
            }
        };
    }

    /** Distinct Baritone messages since {@code since}, newest last, at most {@code max}. */
    public static String baritoneMessages(long since, int max) {
        List<String> lines = new ArrayList<>(new LinkedHashSet<>(ChatLog.since(since, "[Baritone]")));
        if (lines.isEmpty()) return "";
        if (lines.size() > max) lines = lines.subList(lines.size() - max, lines.size());
        return "\nBaritone said: " + String.join(" | ", lines);
    }

    /**
     * Without Baritone: walk (straight line) to the nearest exposed matching block, break it with the best
     * tool and step onto it to pick up the drop. Repeats until {@code count} blocks are broken.
     */
    public static Controller.Task fallbackTask(Set<Block> blocks, int count, int radius) {
        int startBroken = broken(blocks);
        return new Controller.Task() {
            BlockPos target;
            Controller.Task sub;
            int phase; // 0 pick, 1 walk, 2 mine, 3 collect
            final Set<BlockPos> skip = new java.util.HashSet<>();

            @Override
            public String tick(Minecraft mc) {
                int done = broken(blocks) - startBroken;
                if (done >= count && phase != 3) return "broke " + done + " block(s)";
                var p = mc.player;
                if (sub != null) {
                    String r = sub.tick(mc);
                    if (r == null) return null;
                    sub.onStop();
                    sub = null;
                    if (phase == 1) {
                        if (p.getEyePosition().distanceTo(Vec3.atCenterOf(target)) > p.blockInteractionRange()) {
                            skip.add(target);
                            phase = 0;
                        } else {
                            Inv.bestToolFor(mc.level.getBlockState(target));
                            sub = Controller.mine(target);
                            phase = 2;
                        }
                    } else if (phase == 2) {
                        if (!mc.level.getBlockState(target).isAir()) skip.add(target);
                        sub = Controller.walkTo(Vec3.atBottomCenterOf(target), false, 0.4);
                        phase = 3;
                    } else {
                        phase = 0;
                    }
                    return null;
                }
                if (phase == 3) {
                    phase = 0;
                    return null;
                }
                target = nearestExposed(mc, blocks, radius, skip);
                if (target == null) return done > 0 ? "broke " + done + ", no more reachable blocks within " + radius
                        : Script.FAILED + "no exposed " + ids(blocks) + " within " + radius + " blocks";
                if (p.getEyePosition().distanceTo(Vec3.atCenterOf(target)) <= p.blockInteractionRange()) {
                    Inv.bestToolFor(mc.level.getBlockState(target));
                    sub = Controller.mine(target);
                    phase = 2;
                } else {
                    sub = Controller.walkTo(Vec3.atBottomCenterOf(target), true, 2.0);
                    phase = 1;
                }
                return null;
            }

            @Override
            public void onStop() {
                if (sub != null) sub.onStop();
            }

            @Override
            public String describe() {
                return "gathering " + ids(blocks);
            }
        };
    }

    private static BlockPos nearestExposed(Minecraft mc, Set<Block> blocks, int r, Set<BlockPos> skip) {
        var level = mc.level;
        BlockPos c = mc.player.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-r, -Math.min(r, 8), -r), c.offset(r, Math.min(r, 8), r))) {
            BlockState st = level.getBlockState(pos);
            if (!blocks.contains(st.getBlock()) || skip.contains(pos)) continue;
            boolean exposed = false;
            for (Direction d : Direction.values()) if (level.getBlockState(pos.relative(d)).isAir()) { exposed = true; break; }
            if (!exposed) continue;
            double d = pos.distSqr(c) + Math.abs(pos.getY() - c.getY()) * 4.0;
            if (d < bestD) {
                bestD = d;
                best = pos.immutable();
            }
        }
        return best;
    }
}
