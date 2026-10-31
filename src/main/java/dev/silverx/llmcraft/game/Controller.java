package dev.silverx.llmcraft.game;

import com.mojang.blaze3d.platform.InputConstants;
import dev.silverx.llmcraft.config.ConfigManager;
import dev.silverx.llmcraft.mixin.KeyMappingAccessor;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Drives the local player on the client thread: held keys, smooth camera turns and long-running tasks.
 * Everything is released on {@link #stopAll}, which runs on stop, disconnect, death and world change.
 */
public final class Controller {
    /** A multi-tick action. Return null while running, or a result message when finished. */
    public interface Task {
        String tick(Minecraft mc);

        default void onStop() {}

        String describe();

        /** Fighting: keeps going when the player takes damage and suppresses the flee reflex (it retreats by itself). */
        default boolean combat() {
            return false;
        }
    }

    public static boolean inCombat() {
        for (Running r : TASKS) if (r.task.combat()) return true;
        return false;
    }

    public static boolean has(Class<?> type) {
        for (Running r : TASKS) if (type.isInstance(r.task)) return true;
        return false;
    }

    private record Running(Task task, CompletableFuture<String> future, int timeoutTicks, int[] age) {}

    private static final Map<KeyMapping, Integer> HELD = new HashMap<>();
    private static final List<Running> TASKS = new ArrayList<>();
    private static Float targetYaw;
    private static Float targetPitch;
    private static boolean controlling;
    private static float lastHealth = -1;

    private Controller() {}

    public static boolean isActive() {
        return !HELD.isEmpty() || !TASKS.isEmpty() || targetYaw != null || targetPitch != null;
    }

    // ---- keys ----

    /** Hold a key for {@code ticks} ticks (-1 = until released). */
    public static void hold(KeyMapping key, int ticks) {
        int cap = ConfigManager.get().control.maxActionTicks;
        HELD.put(key, ticks < 0 ? cap : Math.min(ticks, cap));
        setRaw(key, true);
        controlling = true;
    }

    public static void release(KeyMapping key) {
        if (HELD.remove(key) != null) setRaw(key, false);
    }

    private static void setRaw(KeyMapping key, boolean down) {
        ((KeyMappingAccessor) key).llmcraft$setDownRaw(down);
    }

    /** One logical press, as consumed by consumeClick() - works for vanilla and modded keybinds. */
    public static void tap(KeyMapping key) {
        KeyMappingAccessor acc = (KeyMappingAccessor) key;
        acc.llmcraft$setClickCount(acc.llmcraft$getClickCount() + 1);
        if (!HELD.containsKey(key)) HELD.put(key, 2);
        setRaw(key, true);
        controlling = true;
    }

    // ---- camera ----

    public static void look(float yaw, float pitch) {
        targetYaw = Mth.wrapDegrees(yaw);
        targetPitch = Mth.clamp(pitch, -90f, 90f);
        controlling = true;
    }

    public static void lookAt(Vec3 target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float[] yp = anglesTo(mc.player.getEyePosition(), target);
        look(yp[0], yp[1]);
    }

    public static float[] anglesTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) -(Mth.atan2(dy, h) * Mth.RAD_TO_DEG);
        return new float[]{Mth.wrapDegrees(yaw), pitch};
    }

    /** True once the camera reached its target (or none is set). */
    public static boolean lookSettled() {
        return targetYaw == null && targetPitch == null;
    }

    // ---- tasks ----

    public static CompletableFuture<String> run(Task task, int timeoutTicks) {
        CompletableFuture<String> f = new CompletableFuture<>();
        // Keys are capped by maxActionTicks (see hold); whole tasks may run longer (mining, smelting, travel).
        int cap = Math.max(ConfigManager.get().control.maxActionTicks, ConfigManager.get().control.maxTaskSeconds * 20);
        TASKS.add(new Running(task, f, Math.min(timeoutTicks <= 0 ? cap : timeoutTicks, cap), new int[]{0}));
        controlling = true;
        return f;
    }

    public static List<String> describeActive() {
        List<String> out = new ArrayList<>();
        for (Running r : TASKS) out.add(r.task.describe() + " (" + r.age[0] / 20 + "s)");
        for (var e : HELD.entrySet()) out.add("holding " + e.getKey().getName());
        return out;
    }

    public static void stopAll(String reason) {
        Minecraft mc = Minecraft.getInstance();
        for (Running r : new ArrayList<>(TASKS)) {
            try {
                r.task.onStop();
            } catch (Throwable ignored) {
            }
            r.future.complete("stopped: " + reason);
        }
        TASKS.clear();
        for (KeyMapping k : HELD.keySet()) setRaw(k, false);
        boolean hadKeys = !HELD.isEmpty();
        HELD.clear();
        targetYaw = null;
        targetPitch = null;
        if (controlling && mc.gameMode != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
        // Re-sync with keys the player is physically holding right now.
        if (hadKeys && mc.screen == null && mc.getWindow() != null) KeyMapping.setAll();
        controlling = false;
    }

    /** Called at the start of every client tick. */
    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            if (controlling) stopAll("no world");
            return;
        }
        if (!controlling) {
            lastHealth = mc.player.getHealth();
            return;
        }
        var cfg = ConfigManager.get().control;
        if (mc.player.isDeadOrDying()) {
            stopAll("player died");
            return;
        }
        if (cfg.stopOnDamage && !inCombat() && lastHealth >= 0 && mc.player.getHealth() < lastHealth) {
            stopAll("took damage");
            Output.info("Stopped AI control: you took damage.");
            return;
        }
        lastHealth = mc.player.getHealth();
        if (cfg.stopOnPlayerInput && mc.screen == null && playerOverride(mc)) {
            stopAll("player input");
            Output.info("Stopped AI control: you moved.");
            return;
        }

        // camera
        float speed = cfg.lookSpeedDegPerTick;
        if (targetYaw != null) {
            float cur = mc.player.getYRot();
            float diff = Mth.wrapDegrees(targetYaw - cur);
            if (speed <= 0 || Math.abs(diff) <= speed) {
                mc.player.setYRot(cur + diff);
                targetYaw = null;
            } else {
                mc.player.setYRot(cur + Math.signum(diff) * speed);
            }
        }
        if (targetPitch != null) {
            float cur = mc.player.getXRot();
            float diff = targetPitch - cur;
            if (speed <= 0 || Math.abs(diff) <= speed) {
                mc.player.setXRot(targetPitch);
                targetPitch = null;
            } else {
                mc.player.setXRot(cur + Math.signum(diff) * speed);
            }
        }

        // keys
        var it = HELD.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            int left = e.getValue() - 1;
            if (left < 0) {
                setRaw(e.getKey(), false);
                it.remove();
            } else {
                setRaw(e.getKey(), true);
                e.setValue(left);
            }
        }

        // tasks
        for (Running r : new ArrayList<>(TASKS)) {
            r.age[0]++;
            String result;
            try {
                result = r.task.tick(mc);
            } catch (Throwable t) {
                result = "error: " + t;
            }
            if (result == null && r.age[0] >= r.timeoutTicks) result = "timed out after " + r.age[0] / 20 + "s";
            if (result != null) {
                try {
                    r.task.onStop();
                } catch (Throwable ignored) {
                }
                TASKS.remove(r);
                r.future.complete(result);
            }
        }
        if (!isActive()) controlling = false;
    }

    private static boolean playerOverride(Minecraft mc) {
        var o = mc.options;
        for (KeyMapping k : new KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump}) {
            if (HELD.containsKey(k)) continue;
            InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(k);
            if (bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() != InputConstants.UNKNOWN.getValue()
                    && InputConstants.isKeyDown(mc.getWindow(), bound.getValue())) {
                return true;
            }
        }
        return false;
    }

    // ---- built-in tasks ----

    /** Walk in a straight line to x/z (and optionally y), jumping over 1-block steps. */
    public static Task walkTo(Vec3 target, boolean sprint, double tolerance) {
        return new Task() {
            Vec3 lastPos;
            int stuck;

            @Override
            public String tick(Minecraft mc) {
                var p = mc.player;
                Vec3 pos = p.position();
                double dx = target.x - pos.x, dz = target.z - pos.z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist <= tolerance) return String.format("arrived at %.1f %.1f %.1f", pos.x, pos.y, pos.z);
                float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
                targetYaw = Mth.wrapDegrees(yaw);
                String danger = Danger.aheadOf(mc, dx / dist, dz / dist);
                if (danger != null) return String.format("stopped at %.1f %.1f %.1f: %s", pos.x, pos.y, pos.z, danger);
                hold(mc.options.keyUp, 2);
                if (sprint) hold(mc.options.keySprint, 2);
                if (p.horizontalCollision && p.onGround()) hold(mc.options.keyJump, 2);
                if (p.isInWater()) hold(mc.options.keyJump, 2);
                if (lastPos != null && lastPos.distanceToSqr(pos) < 0.0004) {
                    if (++stuck > 30) {
                        String why = Stuck.diagnose(mc);
                        return String.format("stuck at %.1f %.1f %.1f, %.1f blocks from target", pos.x, pos.y, pos.z, dist) + (why != null ? " (" + why + ")" : "");
                    }
                } else stuck = 0;
                lastPos = pos;
                return null;
            }

            @Override
            public void onStop() {
                release(mc().options.keyUp);
                release(mc().options.keySprint);
                release(mc().options.keyJump);
            }

            @Override
            public String describe() {
                return String.format("walking to %.0f %.0f", target.x, target.z);
            }
        };
    }

    /** Look at a block and break it, like holding left click. */
    public static Task mine(BlockPos pos) {
        return new Task() {
            boolean started;

            @Override
            public String tick(Minecraft mc) {
                var level = mc.level;
                var p = mc.player;
                if (level.getBlockState(pos).isAir()) return "block at " + pos.toShortString() + " broken";
                Vec3 center = Vec3.atCenterOf(pos);
                if (p.getEyePosition().distanceTo(center) > p.blockInteractionRange() + 0.5) {
                    return "block at " + pos.toShortString() + " is out of reach (" + String.format("%.1f", p.getEyePosition().distanceTo(center)) + " blocks)";
                }
                lookAt(center);
                Direction face = Direction.getApproximateNearest(p.getEyePosition().subtract(center));
                if (!started) {
                    mc.gameMode.startDestroyBlock(pos, face);
                    started = true;
                } else {
                    mc.gameMode.continueDestroyBlock(pos, face);
                }
                p.swing(InteractionHand.MAIN_HAND);
                return null;
            }

            @Override
            public void onStop() {
                if (mc().gameMode != null) mc().gameMode.stopDestroyBlock();
            }

            @Override
            public String describe() {
                return "mining " + pos.toShortString();
            }
        };
    }

    public static Task waitTicks(int ticks) {
        return new Task() {
            int left = ticks;

            @Override
            public String tick(Minecraft mc) {
                return --left <= 0 ? "waited " + ticks / 20.0 + "s" : null;
            }

            @Override
            public String describe() {
                return "waiting";
            }
        };
    }

    /** Completes when the camera reached its target. */
    public static Task settleLook() {
        return new Task() {
            @Override
            public String tick(Minecraft mc) {
                return lookSettled() ? String.format("looking at yaw %.1f pitch %.1f", mc.player.getYRot(), mc.player.getXRot()) : null;
            }

            @Override
            public String describe() {
                return "turning";
            }
        };
    }

    /** Hold keys for a number of ticks, finishing when they are released. */
    public static Task holdFor(List<KeyMapping> keys, int ticks) {
        for (KeyMapping k : keys) hold(k, ticks);
        return new Task() {
            int left = ticks;

            @Override
            public String tick(Minecraft mc) {
                return --left <= 0 ? "done" : null;
            }

            @Override
            public void onStop() {
                for (KeyMapping k : keys) release(k);
            }

            @Override
            public String describe() {
                return "holding keys";
            }
        };
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
