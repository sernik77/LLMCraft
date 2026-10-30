package dev.silverx.llmcraft.tool.impl;

import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.game.Controller;
import dev.silverx.llmcraft.game.Snapshot;
import dev.silverx.llmcraft.tool.Args;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Generic GUI access: works for vanilla screens and for screens added by other mods. */
public final class ScreenTools {
    private ScreenTools() {}

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    public static void register(List<Tool> out) {
        out.add(Tool.builder("read_screen", Category.SCREEN,
                        "Describe the open GUI: title, type, buttons/text fields (with widget indexes) and, for containers, every slot (slot ids for click_slot).")
                .param("include_empty_slots", "boolean", "List empty slots too (default false)", false)
                .sync((ctx, a) -> ToolResult.ok(describe(mc().screen, Args.bool(a, "include_empty_slots", false))))
                .build());

        out.add(Tool.builder("click_widget", Category.SCREEN, "Click a button/checkbox/widget of the open GUI by its index from read_screen or by its label text.")
                .param("index", "integer", "Widget index from read_screen", false)
                .param("label", "string", "Case-insensitive label text (used when index is missing)", false)
                .param("right_click", "boolean", "Use the right mouse button", false)
                .summary(a -> "click widget " + (Args.has(a, "index") ? "#" + Args.integer(a, "index", -1) : "\"" + Args.str(a, "label", "") + "\""))
                .sync((ctx, a) -> {
                    Screen s = mc().screen;
                    if (s == null) return ToolResult.error("no screen is open");
                    AbstractWidget w = find(s, a);
                    if (w == null) return ToolResult.error("widget not found; call read_screen");
                    if (!w.active || !w.visible) return ToolResult.error("widget is disabled or hidden");
                    double x = w.getX() + w.getWidth() / 2.0, y = w.getY() + w.getHeight() / 2.0;
                    var info = new MouseButtonInfo(Args.bool(a, "right_click", false) ? GLFW.GLFW_MOUSE_BUTTON_RIGHT : GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
                    String label = w.getMessage().getString();
                    s.mouseClicked(new MouseButtonEvent(x, y, info), false);
                    s.mouseReleased(new MouseButtonEvent(x, y, info));
                    return ToolResult.ok("clicked \"" + label + "\"; screen now: " + Snapshot.screenName(mc().screen));
                }).build());

        out.add(Tool.builder("type_text", Category.SCREEN, "Type text into a text field of the open GUI (sign, anvil, book, mod config...).")
                .param("text", "string", "Text to type", true)
                .param("index", "integer", "Widget index of the text field (default: focused or first text field)", false)
                .param("replace", "boolean", "Clear the field first (default true)", false)
                .param("press_enter", "boolean", "Press Enter afterwards", false)
                .sync((ctx, a) -> {
                    Screen s = mc().screen;
                    if (s == null) return ToolResult.error("no screen is open");
                    String text = Args.reqStr(a, "text");
                    EditBox box = null;
                    List<AbstractWidget> ws = widgets(s);
                    if (Args.has(a, "index")) {
                        int i = Args.integer(a, "index", -1);
                        if (i >= 0 && i < ws.size() && ws.get(i) instanceof EditBox eb) box = eb;
                    } else {
                        for (AbstractWidget w : ws) if (w instanceof EditBox eb && eb.isFocused()) box = eb;
                        if (box == null) for (AbstractWidget w : ws) if (w instanceof EditBox eb) { box = eb; break; }
                    }
                    if (box != null) {
                        s.setFocused(box);
                        if (Args.bool(a, "replace", true)) box.setValue("");
                    }
                    // Type like a keyboard so screens that are not EditBoxes (signs, books) receive it as well.
                    text.codePoints().forEach(cp -> s.charTyped(new CharacterEvent(cp, 0)));
                    if (Args.bool(a, "press_enter", false)) s.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
                    return ToolResult.ok("typed " + text.length() + " chars" + (box != null ? ", field now: \"" + box.getValue() + "\"" : ""));
                }).build());

        out.add(Tool.builder("click_slot", Category.SCREEN,
                        "Click a slot of the open container (chest, furnace, crafting, inventory...). Actions: pickup (normal click), quick_move (shift-click), swap (with hotbar_slot), throw, pickup_all (double click), clone (creative middle click).")
                .param("slot", "integer", "Slot id from read_screen (-999 = outside the window to drop the carried item)", true)
                .enumParam("action", "Default pickup", false, "pickup", "quick_move", "swap", "throw", "pickup_all", "clone")
                .param("right_click", "boolean", "Right button (pickup: take half / place one)", false)
                .param("hotbar_slot", "integer", "For swap: hotbar slot 0-8 (40 = offhand)", false)
                .summary(a -> Args.str(a, "action", "pickup") + " slot " + Args.integer(a, "slot", -1))
                .sync((ctx, a) -> {
                    if (!(mc().screen instanceof AbstractContainerScreen<?> cs)) return ToolResult.error("no container screen is open");
                    var menu = cs.getMenu();
                    int slot = Args.integer(a, "slot", -1);
                    if (slot != -999 && (slot < 0 || slot >= menu.slots.size())) return ToolResult.error("slot out of range 0-" + (menu.slots.size() - 1));
                    String action = Args.str(a, "action", "pickup").toLowerCase(Locale.ROOT);
                    ClickType type;
                    int button = Args.bool(a, "right_click", false) ? 1 : 0;
                    switch (action) {
                        case "quick_move" -> type = ClickType.QUICK_MOVE;
                        case "swap" -> { type = ClickType.SWAP; button = Args.integer(a, "hotbar_slot", 0); }
                        case "throw" -> type = ClickType.THROW;
                        case "pickup_all" -> type = ClickType.PICKUP_ALL;
                        case "clone" -> { type = ClickType.CLONE; button = 2; }
                        default -> type = ClickType.PICKUP;
                    }
                    mc().gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, mc().player);
                    String after = slot >= 0 ? Snapshot.stack(menu.slots.get(slot).getItem()) : "-";
                    return ToolResult.ok(action + " slot " + slot + " -> slot now: " + after + " | cursor: " + Snapshot.stack(menu.getCarried()));
                }).build());

        out.add(Tool.builder("close_screen", Category.SCREEN, "Close the open GUI (like pressing Escape).")
                .sync((ctx, a) -> {
                    Screen s = mc().screen;
                    if (s == null) return ToolResult.ok("no screen was open");
                    String name = Snapshot.screenName(s);
                    s.onClose();
                    if (mc().screen == s) mc().setScreen(null);
                    return ToolResult.ok("closed " + name);
                }).build());
    }

    static List<AbstractWidget> widgets(Screen s) {
        List<AbstractWidget> out = new ArrayList<>();
        collect(s.children(), out);
        return out;
    }

    private static void collect(List<? extends GuiEventListener> list, List<AbstractWidget> out) {
        for (GuiEventListener l : list) {
            if (l instanceof AbstractWidget w) out.add(w);
            if (l instanceof ContainerEventHandler c) collect(c.children(), out);
        }
    }

    static AbstractWidget find(Screen s, com.google.gson.JsonObject a) {
        List<AbstractWidget> ws = widgets(s);
        if (Args.has(a, "index")) {
            int i = Args.integer(a, "index", -1);
            return i >= 0 && i < ws.size() ? ws.get(i) : null;
        }
        String label = Args.str(a, "label", "").toLowerCase(Locale.ROOT).trim();
        if (label.isEmpty()) return null;
        for (AbstractWidget w : ws) if (w.getMessage().getString().toLowerCase(Locale.ROOT).trim().equals(label)) return w;
        for (AbstractWidget w : ws) if (w.getMessage().getString().toLowerCase(Locale.ROOT).contains(label)) return w;
        return null;
    }

    public static String describe(Screen s, boolean includeEmpty) {
        if (s == null) return "No GUI open (player is in game).";
        StringBuilder sb = new StringBuilder("Screen: ").append(Snapshot.screenName(s)).append(" class ").append(s.getClass().getName()).append('\n');
        List<AbstractWidget> ws = widgets(s);
        if (!ws.isEmpty()) {
            sb.append("Widgets:\n");
            for (int i = 0; i < ws.size(); i++) {
                AbstractWidget w = ws.get(i);
                if (!w.visible) continue;
                sb.append("  #").append(i).append(' ').append(w.getClass().getSimpleName());
                String msg = w.getMessage().getString();
                if (!msg.isBlank()) sb.append(" \"").append(msg).append('"');
                if (w instanceof EditBox eb) sb.append(" value=\"").append(eb.getValue()).append('"');
                if (!w.active) sb.append(" (disabled)");
                if (w.isFocused()) sb.append(" (focused)");
                sb.append('\n');
            }
        }
        if (s instanceof AbstractContainerScreen<?> cs) {
            var menu = cs.getMenu();
            sb.append("Container slots (id: item) - [P] = player inventory:\n");
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (!includeEmpty && !slot.hasItem()) continue;
                sb.append("  ").append(i).append(slot.container instanceof Inventory ? " [P]" : "").append(": ").append(Snapshot.stack(slot.getItem())).append('\n');
            }
            sb.append("Cursor (carried): ").append(Snapshot.stack(menu.getCarried())).append('\n');
        }
        var active = Controller.describeActive();
        if (!active.isEmpty()) sb.append("AI actions running: ").append(active);
        return sb.toString().trim();
    }
}
