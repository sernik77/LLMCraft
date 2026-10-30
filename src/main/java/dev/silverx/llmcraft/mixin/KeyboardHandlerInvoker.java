package dev.silverx.llmcraft.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the AI simulate physical key presses, so hotkeys of mods with their own input systems work too. */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {
    @Invoker("keyPress")
    void llmcraft$keyPress(long window, int action, KeyEvent event);

    @Invoker("charTyped")
    void llmcraft$charTyped(long window, CharacterEvent event);
}
