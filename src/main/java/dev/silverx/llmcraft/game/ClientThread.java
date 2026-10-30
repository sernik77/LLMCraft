package dev.silverx.llmcraft.game;

import net.minecraft.client.Minecraft;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/** Runs work on the Minecraft client thread and hands the result back as a future. */
public final class ClientThread {
    private ClientThread() {}

    public static <T> CompletableFuture<T> call(Callable<T> task) {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<T> f = new CompletableFuture<>();
        Runnable r = () -> {
            try {
                f.complete(task.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        };
        if (mc.isSameThread()) r.run();
        else mc.execute(r);
        return f;
    }

    public static void run(Runnable r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) r.run();
        else mc.execute(r);
    }
}
