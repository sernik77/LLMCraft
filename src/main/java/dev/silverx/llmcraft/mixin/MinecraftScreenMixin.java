package dev.silverx.llmcraft.mixin;

import dev.silverx.llmcraft.LlmCraft;
import dev.silverx.llmcraft.agent.Agent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostics: logs who closes or replaces a container window while the AI is working, so a mod or key
 * that keeps closing GUIs can be identified from latest.log. Closes done by LLMCraft itself are ignored.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftScreenMixin {
    @Shadow
    public Screen screen;

    @Inject(method = "setScreen", at = @At("HEAD"), require = 0)
    private void llmcraft$traceScreenChange(Screen next, CallbackInfo ci) {
        if (!(screen instanceof AbstractContainerScreen<?>) || next == screen || !Agent.busy()) return;
        List<String> frames = new ArrayList<>();
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            String c = e.getClassName();
            if (c.startsWith("dev.silverx.llmcraft") && !c.contains("MinecraftScreenMixin")) return;
            if (c.startsWith("java.") || c.contains("MinecraftScreenMixin") || c.equals(Minecraft.class.getName()) && e.getMethodName().equals("setScreen")) continue;
            if (frames.size() < 8) frames.add(c.substring(c.lastIndexOf('.') + 1) + "." + e.getMethodName());
        }
        LlmCraft.LOGGER.info("[diagnostic] {} was replaced by {} during an AI request; caller: {}", screen.getClass().getSimpleName(),
                next == null ? "nothing (closed)" : next.getClass().getSimpleName(), String.join(" <- ", frames));
    }
}
