package dev.silverx.llmcraft.game;

import dev.silverx.llmcraft.agent.Agent;
import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Survival reflexes while the AI is working: when the player is drowning or in lava, every AI action and
 * Baritone are stopped (and the player swims up). The model is told about it in its next tool result.
 */
public final class Safety {
    private static String pending;
    private static long lastAt;

    private Safety() {}

    /** Called every client tick. */
    public static void tick(Minecraft mc) {
        var p = mc.player;
        if (p == null || p.isDeadOrDying() || p.isCreative() || p.isSpectator()) return;
        if (!Agent.busy() && !Controller.isActive()) return;
        long now = System.currentTimeMillis();
        if (now - lastAt < 5000) return;
        String hazard = null;
        boolean swimUp = false;
        if (p.isUnderWater() && p.getAirSupply() < p.getMaxAirSupply() / 3) {
            hazard = String.format(Locale.ROOT, "drowning (air %d/%d)", Math.max(0, p.getAirSupply()), p.getMaxAirSupply());
            swimUp = true;
        } else if (p.isInLava()) {
            hazard = "standing in lava";
        }
        if (hazard == null) return;
        lastAt = now;
        boolean baritone = BaritoneBridge.available() && BaritoneBridge.isBusy();
        Controller.stopAll(hazard);
        if (baritone) BaritoneBridge.cancel();
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        else if (mc.screen != null && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) mc.setScreen(null);
        if (swimUp) Controller.hold(mc.options.keyJump, 60);
        String msg = "SAFETY: " + hazard + " - stopped all AI actions" + (baritone ? " and Baritone" : "") + (swimUp ? ", swimming up" : "")
                + ". Get to safety before continuing.";
        Output.error(msg);
        synchronized (Safety.class) {
            pending = msg;
        }
    }

    /** Queues a message for the model's next tool result (and shows it to the player). */
    public static void report(String msg) {
        Output.error(msg);
        synchronized (Safety.class) {
            pending = pending == null ? msg : pending + "\n" + msg;
        }
    }

    /** The hazard that happened since the last call, or null. */
    public static synchronized String take() {
        String s = pending;
        pending = null;
        return s;
    }
}
