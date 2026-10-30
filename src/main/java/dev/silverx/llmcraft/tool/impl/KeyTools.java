package dev.silverx.llmcraft.tool.impl;

import com.mojang.blaze3d.platform.InputConstants;
import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.game.Controller;
import dev.silverx.llmcraft.mixin.KeyboardHandlerInvoker;
import dev.silverx.llmcraft.tool.Args;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolResult;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Keybinds of vanilla and every other mod, plus raw key simulation for mods with their own key handling. */
public final class KeyTools {
    private KeyTools() {}

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public static void register(List<Tool> out) {
        out.add(Tool.builder("list_keybinds", Category.KEYBINDS, "List keybinds (vanilla and all mods) with their id, label, category and bound key.")
                .param("filter", "string", "Optional text to match id/label/category", false)
                .sync((ctx, a) -> {
                    String f = Args.str(a, "filter", "").toLowerCase(Locale.ROOT);
                    StringBuilder sb = new StringBuilder();
                    int n = 0;
                    for (KeyMapping k : mc().options.keyMappings) {
                        String label = Component.translatable(k.getName()).getString();
                        String cat = k.getCategory().label().getString();
                        String line = k.getName() + " | " + label + " | " + cat + " | " + k.getTranslatedKeyMessage().getString();
                        if (!f.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(f)) continue;
                        sb.append(line).append('\n');
                        if (++n >= 150) {
                            sb.append("... (use a filter)");
                            break;
                        }
                    }
                    return ToolResult.ok(sb.isEmpty() ? "no keybinds match" : sb.toString().trim());
                }).build());

        out.add(Tool.builder("press_keybind", Category.KEYBINDS,
                        "Trigger a keybind by its id from list_keybinds (e.g. 'key.inventory' or a mod's 'key.xaero_world_map'). tap = one press, hold = keep it down for seconds.")
                .param("id", "string", "Keybind id", true)
                .enumParam("mode", "Default tap", false, "tap", "hold")
                .param("seconds", "number", "For hold (default 1)", false)
                .summary(a -> Args.str(a, "mode", "tap") + " keybind " + Args.str(a, "id", ""))
                .async((ctx, a) -> {
                    KeyMapping k = KeyMapping.get(Args.reqStr(a, "id"));
                    if (k == null) return CompletableFuture.completedFuture(ToolResult.error("unknown keybind id; use list_keybinds"));
                    if ("hold".equalsIgnoreCase(Args.str(a, "mode", "tap"))) {
                        int ticks = (int) Math.max(1, Math.round(Args.num(a, "seconds", 1) * 20));
                        return Controller.run(Controller.holdFor(List.of(k), ticks), ticks + 10).thenApply(r -> ToolResult.ok("held " + k.getName()));
                    }
                    Controller.tap(k);
                    return Controller.run(Controller.waitTicks(3), 10).thenApply(r -> ToolResult.ok("pressed " + k.getName()));
                }).build());

        out.add(Tool.builder("press_key", Category.KEYBINDS,
                        "Simulate a physical keyboard key press (reaches every mod, including ones with their own hotkey systems, and open GUIs). Key names: 'a'..'z', '0'..'9', 'f1'..'f12', 'escape', 'enter', 'tab', 'space', 'left.shift', 'right.shift', 'up', 'down', 'left', 'right', 'delete', 'backspace', or full ids like 'key.keyboard.right.shift'.")
                .param("key", "string", "Key name", true)
                .param("hold_seconds", "number", "How long to hold (default 0.1)", false)
                .arrayParam("modifiers", "string", "Any of: shift, control, alt", false)
                .summary(a -> "press key " + Args.str(a, "key", ""))
                .async((ctx, a) -> {
                    String name = Args.reqStr(a, "key").toLowerCase(Locale.ROOT).trim();
                    InputConstants.Key key;
                    try {
                        key = InputConstants.getKey(name.startsWith("key.") ? name : "key.keyboard." + name);
                    } catch (Exception e) {
                        return CompletableFuture.completedFuture(ToolResult.error("unknown key '" + name + "'"));
                    }
                    if (key == InputConstants.UNKNOWN || key.getType() != InputConstants.Type.KEYSYM)
                        return CompletableFuture.completedFuture(ToolResult.error("unknown key '" + name + "'"));
                    int mods = 0;
                    var arr = a.get("modifiers");
                    if (arr != null && arr.isJsonArray()) {
                        for (var m : arr.getAsJsonArray()) {
                            switch (m.getAsString().toLowerCase(Locale.ROOT)) {
                                case "shift" -> mods |= GLFW.GLFW_MOD_SHIFT;
                                case "control", "ctrl" -> mods |= GLFW.GLFW_MOD_CONTROL;
                                case "alt" -> mods |= GLFW.GLFW_MOD_ALT;
                                default -> {}
                            }
                        }
                    }
                    long window = mc().getWindow().handle();
                    KeyEvent ev = new KeyEvent(key.getValue(), 0, mods);
                    var handler = (KeyboardHandlerInvoker) mc().keyboardHandler;
                    handler.llmcraft$keyPress(window, GLFW.GLFW_PRESS, ev);
                    int ticks = (int) Math.max(1, Math.round(Args.num(a, "hold_seconds", 0.1) * 20));
                    String keyName = key.getDisplayName().getString();
                    return Controller.run(Controller.waitTicks(ticks), ticks + 5).thenApply(r -> {
                        handler.llmcraft$keyPress(window, GLFW.GLFW_RELEASE, ev);
                        return ToolResult.ok("pressed " + keyName);
                    });
                }).build());
    }

    /** Used by /llm status: shows how a keybind is bound. */
    public static String bound(KeyMapping k) {
        return KeyBindingHelper.getBoundKeyOf(k).getDisplayName().getString();
    }
}
