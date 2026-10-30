package dev.silverx.llmcraft.game;

import dev.silverx.llmcraft.config.ConfigManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Local-only chat output (never sent to the server). */
public final class Output {
    private Output() {}

    private static MutableComponent prefix() {
        String name = ConfigManager.get().chat.displayName;
        return Component.literal("[" + (name == null || name.isBlank() ? "AI" : name) + "] ").withStyle(ChatFormatting.GOLD);
    }

    public static void show(Component c) {
        ClientThread.run(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui != null) mc.gui.getChat().addMessage(c);
        });
    }

    public static void reply(String text) {
        show(prefix().append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
    }

    public static void info(String text) {
        show(prefix().append(Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }

    public static void tool(String text) {
        show(prefix().append(Component.literal("⚙ " + text).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)));
    }

    public static void thinking(String text) {
        show(prefix().append(Component.literal("💭 " + text).withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.ITALIC)));
    }

    public static void error(String text) {
        show(prefix().append(Component.literal(text).withStyle(ChatFormatting.RED)));
    }

    public static MutableComponent button(String label, ChatFormatting color, String command, String hover) {
        return Component.literal("[" + label + "]").withStyle(Style.EMPTY.withColor(color).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    public static MutableComponent header() {
        return prefix();
    }
}
