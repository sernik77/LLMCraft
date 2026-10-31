package dev.silverx.llmcraft.mixin;

import dev.silverx.llmcraft.game.ChatLog;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client mods such as Baritone and Meteor print straight into the chat HUD, bypassing the message
 * events. Capture those lines so the AI sees their feedback ("Unable to find any path", "Toggled ...").
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V", at = @At("HEAD"), require = 0)
    private void llmcraft$capture(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
        if (message != null) ChatLog.addFromHud(message.getString());
    }
}
