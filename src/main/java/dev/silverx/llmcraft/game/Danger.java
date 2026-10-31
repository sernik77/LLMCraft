package dev.silverx.llmcraft.game;

import dev.silverx.llmcraft.agent.Agent;
import dev.silverx.llmcraft.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Threats around the player and reflexes while the AI is acting: run from a hissing creeper, run away
 * when weak with monsters close, never walk into lava or off a high drop. Never takes over while the
 * player plays alone (only when the AI is working).
 */
public final class Danger {
    private Danger() {}

    private static int tick;
    private static long lastReflexAt;

    /** Hostile mobs within the radius, nearest first (endermen and zombified piglins are neutral and left out). */
    public static List<LivingEntity> hostiles(Minecraft mc, double radius) {
        var p = mc.player;
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || !le.isAlive() || !(e instanceof Enemy)) continue;
            if (e instanceof EnderMan || e instanceof ZombifiedPiglin) continue;
            if (e.distanceTo(p) <= radius) out.add(le);
        }
        out.sort(Comparator.comparingDouble(e -> e.distanceTo(p)));
        return out;
    }

    /** One line about threats for the status line, or null. */
    public static String summary(Minecraft mc) {
        var list = hostiles(mc, 16);
        if (list.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(4, list.size()); i++) {
            LivingEntity e = list.get(i);
            parts.add(String.format(Locale.ROOT, "%s %.0fm", Inv.entityName(e), e.distanceTo(mc.player)));
        }
        return "threats: " + String.join(", ", parts) + (list.size() > 4 ? " +" + (list.size() - 4) : "");
    }

    /**
     * What makes the next step in this direction dangerous: lava, or a drop of 4+ blocks (no water below).
     * Null when it is safe.
     */
    public static String aheadOf(Minecraft mc, double dirX, double dirZ) {
        var p = mc.player;
        var level = mc.level;
        BlockPos ahead = BlockPos.containing(p.getX() + dirX * 0.8, p.getY() + 0.1, p.getZ() + dirZ * 0.8);
        if (ahead.equals(p.blockPosition())) return null;
        for (int dy = 0; dy >= -2; dy--) {
            var st = level.getBlockState(ahead.above(dy));
            if (st.is(Blocks.LAVA) || st.is(Blocks.FIRE) || st.is(Blocks.MAGMA_BLOCK) && dy == -1) return "lava/fire ahead";
        }
        if (!p.onGround() || p.isInWater()) return null;
        if (!level.getBlockState(ahead).getCollisionShape(level, ahead).isEmpty()) return null; // a wall or a step up
        for (int dy = 1; dy <= 4; dy++) {
            BlockPos below = ahead.below(dy);
            if (!level.getFluidState(below).isEmpty()) return level.getBlockState(below).is(Blocks.LAVA) ? "lava below ahead" : null;
            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) return null;
        }
        if (ahead.getY() - 4 <= level.getMinY()) return "the void below ahead";
        return "a drop of 4+ blocks ahead";
    }

    /** Called every client tick: reflexes, only while the AI is working. */
    public static void tick(Minecraft mc) {
        var p = mc.player;
        if (p == null || p.isDeadOrDying() || p.isCreative() || p.isSpectator()) return;
        if (++tick % 4 != 0) return;
        if (!Agent.busy() && !Controller.isActive()) return;
        var cfg = ConfigManager.get().control;
        if (!cfg.autoFlee || Controller.inCombat() || Controller.has(FleeTask.class)) return;
        long now = System.currentTimeMillis();
        if (now - lastReflexAt < 3000) return;
        String why = null;
        List<LivingEntity> near = hostiles(mc, 8);
        for (LivingEntity e : near) {
            if (e instanceof Creeper c && c.getSwelling(1f) > 0.05f && c.distanceTo(p) < 5) {
                why = "a creeper is about to explode " + String.format(Locale.ROOT, "%.0fm", c.distanceTo(p)) + " away";
                break;
            }
        }
        if (why == null && p.getHealth() <= cfg.fleeHealth && !near.isEmpty() && near.getFirst().distanceTo(p) < 6) {
            why = String.format(Locale.ROOT, "low health (%.0f) with %s %.0fm away", p.getHealth(), Inv.entityName(near.getFirst()), near.getFirst().distanceTo(p));
        }
        if (why == null) return;
        lastReflexAt = now;
        boolean baritone = BaritoneBridge.available() && BaritoneBridge.isBusy();
        Controller.stopAll(why);
        if (baritone) BaritoneBridge.cancel();
        Controller.run(new FleeTask(12), 20 * 8);
        Safety.report("SAFETY: " + why + " - stopped what you were doing and ran away. Heal (eat) and deal with the threat before going on.");
    }

    /** Sprints away from the nearest threats for `distance` blocks, turning away from drops and lava. */
    public static final class FleeTask implements Controller.Task {
        private final double distance;
        private Vec3 start;
        private int stuck;
        private Vec3 last;

        public FleeTask(double distance) {
            this.distance = distance;
        }

        @Override
        public String tick(Minecraft mc) {
            var p = mc.player;
            if (start == null) start = p.position();
            double gone = p.position().distanceTo(start);
            List<LivingEntity> threats = hostiles(mc, 16);
            if (gone >= distance) return String.format(Locale.ROOT, "ran %.0f blocks away", gone);
            Vec3 away;
            if (threats.isEmpty()) away = p.position().subtract(start);
            else {
                Vec3 c = Vec3.ZERO;
                for (LivingEntity e : threats) c = c.add(e.position());
                away = p.position().subtract(c.scale(1.0 / threats.size()));
            }
            if (away.horizontalDistanceSqr() < 1e-4) away = new Vec3(1, 0, 0);
            double base = Math.atan2(away.z, away.x);
            double chosen = Double.NaN;
            for (double off : new double[]{0, 0.6, -0.6, 1.2, -1.2, 1.8, -1.8}) {
                double ang = base + off;
                if (aheadOf(mc, Math.cos(ang), Math.sin(ang)) == null) {
                    chosen = ang;
                    break;
                }
            }
            if (Double.isNaN(chosen)) return Script.FAILED + "no safe direction to run (drops/lava around) - fight or block instead";
            p.setYRot(Mth.wrapDegrees((float) (chosen * Mth.RAD_TO_DEG) - 90f));
            Controller.hold(mc.options.keyUp, 2);
            Controller.hold(mc.options.keySprint, 2);
            if (p.horizontalCollision && p.onGround() || p.isInWater()) Controller.hold(mc.options.keyJump, 2);
            if (last != null && last.distanceToSqr(p.position()) < 0.001) {
                if (++stuck > 20) return Script.FAILED + "stuck while running away" + (Stuck.diagnose(mc) != null ? " (" + Stuck.diagnose(mc) + ")" : "");
            } else stuck = 0;
            last = p.position();
            return null;
        }

        @Override
        public void onStop() {
            var o = Minecraft.getInstance().options;
            Controller.release(o.keyUp);
            Controller.release(o.keySprint);
            Controller.release(o.keyJump);
        }

        @Override
        public String describe() {
            return "running away";
        }
    }
}
