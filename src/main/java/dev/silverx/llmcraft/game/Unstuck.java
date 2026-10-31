package dev.silverx.llmcraft.game;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Gets the player out of a hole, a dead end, water or a cobweb. Strategies are picked from the diagnosis
 * and each one stops early when nothing moves; after two attempts without progress it gives up and
 * reports why, so the model re-thinks instead of looping.
 */
public final class Unstuck {
    private Unstuck() {}

    private record Attempt(String name, Supplier<CompletableFuture<String>> run) {}

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public static CompletableFuture<String> run() {
        String diag = Stuck.diagnose(mc());
        Vec3 start = mc().player.position();
        List<Attempt> attempts = new ArrayList<>();
        var p = mc().player;
        BlockPos feet = p.blockPosition();
        boolean baritone = BaritoneBridge.available();
        if (diag != null && diag.contains("cobweb")) {
            attempts.add(new Attempt("cut the cobweb", () -> Controller.run(Controller.mine(feet), 20 * 4)));
        }
        if (p.isInWater() || p.isInLava()) {
            attempts.add(new Attempt("swim to land", () -> {
                BlockPos spot = Stuck.openSpot(mc());
                if (spot == null) {
                    Controller.hold(mc().options.keyJump, 60);
                    return Controller.run(Controller.waitTicks(60), 80);
                }
                Controller.hold(mc().options.keyJump, 80);
                return Controller.run(Controller.walkTo(Vec3.atBottomCenterOf(spot), true, 0.5), 20 * 5);
            }));
        }
        if (baritone) attempts.add(new Attempt("Baritone to an open spot", Unstuck::baritoneToOpenSpot));
        if (diag != null && diag.contains("hole") && !diag.contains("buried")) {
            int depth = 0;
            while (depth < 8 && isWall(feet.above(depth))) depth++;
            int climb = Math.max(1, depth);
            attempts.add(new Attempt("pillar up " + climb, () -> Controller.run(Stuck.pillarUp(climb), 20 * 8)));
        }
        attempts.add(new Attempt("dig out sideways", () -> Controller.run(Stuck.digOut(), 20 * 14)));
        return next(attempts, 0, 0, new ArrayList<>(), start, diag);
    }

    private static boolean isWall(BlockPos level) {
        var w = mc().level;
        int n = 0;
        for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos q = level.relative(d);
            if (!w.getBlockState(q).getCollisionShape(w, q).isEmpty()) n++;
        }
        return n >= 3;
    }

    private static CompletableFuture<String> next(List<Attempt> attempts, int idx, int failures, List<String> tried, Vec3 start, String firstDiag) {
        if (failures >= 2 || idx >= attempts.size()) {
            String now = Stuck.diagnose(mc());
            return CompletableFuture.completedFuture(Script.FAILED + "still stuck after " + tried.size() + " attempt(s) (" + String.join("; ", tried) + ")"
                    + (now != null ? ": " + now : "") + ". Stop and re-evaluate: look at what is around (get_block/screenshot once), "
                    + "pick another way (other direction, place blocks, different target) or ask the player.");
        }
        Attempt a = attempts.get(idx);
        Vec3 before = mc().player.position();
        String[] said = {""};
        return a.run.get().thenCompose(r -> ClientThread.call(() -> {
            said[0] = r.replace(Script.FAILED, "");
            Vec3 after = mc().player.position();
            String diag = Stuck.diagnose(mc());
            boolean moved = after.distanceTo(before) >= 1.5 || after.distanceTo(start) >= 2.5;
            boolean trapped = diag != null && (diag.contains("hole") || diag.contains("buried") || diag.contains("dead end") || diag.contains("cobweb")
                    || diag.contains("inside a block"));
            return (diag == null || moved && !trapped) ? "free: " + a.name + " worked (" + r.replace(Script.FAILED, "") + "), now at " + Stuck.pos(mc())
                    + (diag != null ? " - note: " + diag : "") : null;
        })).thenCompose(ok -> {
            if (ok != null) return CompletableFuture.completedFuture(ok);
            tried.add(a.name + " -> " + (said[0].length() > 90 ? said[0].substring(0, 90) + "…" : said[0]));
            return next(attempts, idx + 1, failures + 1, tried, start, firstDiag);
        });
    }

    private static CompletableFuture<String> baritoneToOpenSpot() {
        BlockPos spot = Stuck.openSpot(mc());
        if (spot == null) return CompletableFuture.completedFuture(Script.FAILED + "no open spot within 8 blocks");
        try {
            if (!BaritoneBridge.execute("goto " + spot.getX() + " " + spot.getY() + " " + spot.getZ())) return CompletableFuture.completedFuture(Script.FAILED + "Baritone refused");
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(Script.FAILED + "Baritone failed: " + t.getMessage());
        }
        return Controller.run(Mining.baritoneWatch("escaping to " + spot.toShortString(), () -> mc().player.blockPosition().equals(spot) && mc().player.onGround(), null), 20 * 15);
    }
}
