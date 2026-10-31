package dev.silverx.llmcraft;

import com.mojang.blaze3d.platform.InputConstants;
import dev.silverx.llmcraft.agent.Agent;
import dev.silverx.llmcraft.agent.Approvals;
import dev.silverx.llmcraft.agent.Triggers;
import dev.silverx.llmcraft.command.Commands;
import dev.silverx.llmcraft.config.ConfigManager;
import dev.silverx.llmcraft.data.Stores;
import dev.silverx.llmcraft.game.ChatLog;
import dev.silverx.llmcraft.game.Controller;
import dev.silverx.llmcraft.game.Mining;
import dev.silverx.llmcraft.game.Output;
import dev.silverx.llmcraft.game.Safety;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import dev.silverx.llmcraft.tool.ToolRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LlmCraft implements ClientModInitializer {
    public static final String MOD_ID = "llmcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger("LLMCraft");

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
    private static KeyMapping keyPrompt;
    private static KeyMapping keyStop;
    private static KeyMapping keyApprove;
    private static KeyMapping keyDeny;

    @Override
    public void onInitializeClient() {
        ConfigManager.load();
        ToolRegistry.init();

        keyPrompt = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.llmcraft.prompt", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY));
        keyStop = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.llmcraft.stop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_END, CATEGORY));
        keyApprove = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.llmcraft.approve", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_EQUAL, CATEGORY));
        keyDeny = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.llmcraft.deny", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_MINUS, CATEGORY));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> Commands.register(dispatcher));

        ClientTickEvents.START_CLIENT_TICK.register(Controller::tick);
        ClientTickEvents.START_CLIENT_TICK.register(Safety::tick);
        ClientTickEvents.START_CLIENT_TICK.register(dev.silverx.llmcraft.game.Danger::tick);
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> Mining.onBroken(state));
        ClientTickEvents.END_CLIENT_TICK.register(LlmCraft::endTick);

        // Incoming chat: log it for the AI's context and feed the triggers.
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> {
            Minecraft mc = Minecraft.getInstance();
            boolean self = sender != null && mc.player != null && sender.id().equals(mc.player.getUUID());
            String name = sender == null ? null : sender.name();
            String text = message.getString();
            ChatLog.add(self ? ChatLog.Kind.SELF : ChatLog.Kind.PLAYER, name, text);
            Triggers.onChat(name, text, self);
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString();
            ChatLog.add(overlay ? ChatLog.Kind.ACTIONBAR : ChatLog.Kind.SYSTEM, null, text);
            if (!overlay) Triggers.onChat(null, text, false);
        });

        // "@ai hello" typed in chat goes to the AI and never reaches the server.
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String prefix = ConfigManager.get().chat.promptPrefix;
            if (prefix == null || prefix.isBlank() || !message.startsWith(prefix)) return true;
            String rest = message.substring(prefix.length()).trim();
            if (rest.isEmpty()) Output.info("Usage: " + prefix + " <message>");
            else Agent.submit(rest, Agent.Source.USER);
            return false;
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            Stores.current();
            Triggers.onJoin();
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Agent.cancel("disconnected");
            Approvals.clearSession();
            ChatLog.clear();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Agent.cancel("game closing"));

        LOGGER.info("LLMCraft ready. Config: {}", ConfigManager.configFile());
    }

    private static void endTick(Minecraft mc) {
        while (keyStop.consumeClick()) {
            Agent.cancel("stop key");
            Output.info("Stopped.");
        }
        while (keyApprove.consumeClick()) {
            if (!Approvals.approveLatest()) Output.info("Nothing to approve.");
        }
        while (keyDeny.consumeClick()) {
            if (!Approvals.denyLatest()) Output.info("Nothing to deny.");
        }
        while (keyPrompt.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new ChatScreen("/ai ", false));
        }
        Triggers.tick(mc);
    }
}
