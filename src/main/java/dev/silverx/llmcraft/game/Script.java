package dev.silverx.llmcraft.game;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A multi-tick action written as a list of steps. Each step is ticked until it is done; steps may queue
 * more steps (loops). Container work waits for the server's slot sync instead of guessing delays, and
 * fails with a clear message if the expected container is closed underneath it.
 */
public final class Script implements Controller.Task {
    /** Returned by a step that needs more ticks. */
    public static final String WAIT = new String("wait");
    public static final String FAILED = "FAILED: ";

    @FunctionalInterface
    public interface Step {
        /** {@link #WAIT} to keep going, null when done, anything else is a failure message. */
        String tick(Minecraft mc);
    }

    private record Entry(Step step, int timeoutTicks, String what) {}

    private final String description;
    private final Deque<Entry> steps = new ArrayDeque<>();
    private final List<String> notes = new ArrayList<>();
    private Entry current;
    private int age;
    /** Container the script opened itself; closed again when the script ends. */
    private AbstractContainerMenu opened;
    /** Container the clicks belong to; the script fails if the player's menu changes away from it. */
    private AbstractContainerMenu guarded;

    public Script(String description) {
        this.description = description;
    }

    public Script then(String what, int timeoutTicks, Step step) {
        steps.addLast(new Entry(step, timeoutTicks, what));
        return this;
    }

    /** Runs once (single tick). */
    public Script then(Runnable r) {
        return then("step", 1, mc -> {
            r.run();
            return null;
        });
    }

    public Script waitTicks(int ticks) {
        int[] left = {ticks};
        return then("wait", ticks + 1, mc -> --left[0] <= 0 ? null : WAIT);
    }

    public Script until(String what, int timeoutTicks, BooleanSupplier cond) {
        return then(what, timeoutTicks, mc -> cond.getAsBoolean() ? null : WAIT);
    }

    private void pushNext(List<Entry> list) {
        for (int i = list.size() - 1; i >= 0; i--) steps.addFirst(list.get(i));
    }

    /** Inserts the steps of {@code inner} to run right after the current one (loops decided at runtime). */
    public void pushNext(Script inner) {
        pushNext(new ArrayList<>(inner.steps));
    }

    public void note(String text) {
        notes.add(text);
    }

    public void opened(AbstractContainerMenu menu) {
        this.opened = menu;
        this.guarded = menu;
    }

    public void guard(AbstractContainerMenu menu) {
        this.guarded = menu;
    }

    public AbstractContainerMenu openedMenu() {
        return opened;
    }

    @Override
    public String tick(Minecraft mc) {
        if (guarded != null && mc.player.containerMenu != guarded) {
            return FAILED + "the container window was closed by something else (another mod, the server or a key press)" + notesSuffix();
        }
        if (current == null) {
            current = steps.pollFirst();
            age = 0;
            if (current == null) return String.join("; ", notes).isEmpty() ? "done" : String.join("; ", notes);
        }
        age++;
        String r;
        try {
            r = current.step.tick(mc);
        } catch (Throwable t) {
            r = t.toString();
        }
        if (r == WAIT) {
            if (age >= current.timeoutTicks) return FAILED + "timed out while " + current.what + notesSuffix();
            return null;
        }
        if (r != null) return FAILED + r + notesSuffix();
        current = null;
        return null;
    }

    private String notesSuffix() {
        return notes.isEmpty() ? "" : " (done so far: " + String.join("; ", notes) + ")";
    }

    @Override
    public void onStop() {
        Minecraft mc = Minecraft.getInstance();
        if (opened != null && mc.player != null && mc.player.containerMenu == opened) mc.player.closeContainer();
        opened = null;
        guarded = null;
    }

    @Override
    public String describe() {
        return description;
    }

    public static boolean failed(String result) {
        return result != null && (result.startsWith(FAILED) || result.startsWith("stopped:") || result.startsWith("timed out") || result.startsWith("error:"));
    }
}
