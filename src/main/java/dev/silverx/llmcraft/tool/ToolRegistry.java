package dev.silverx.llmcraft.tool;

import dev.silverx.llmcraft.tool.impl.ChatTools;
import dev.silverx.llmcraft.tool.impl.ControlTools;
import dev.silverx.llmcraft.tool.impl.InfoTools;
import dev.silverx.llmcraft.tool.impl.KeyTools;
import dev.silverx.llmcraft.tool.impl.MemoryTools;
import dev.silverx.llmcraft.tool.impl.ModTools;
import dev.silverx.llmcraft.tool.impl.ScreenTools;
import dev.silverx.llmcraft.tool.impl.VisionTools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** All tools. Other mods can add their own through {@link #register(Tool)} (see README). */
public final class ToolRegistry {
    private static final List<Tool> TOOLS = new ArrayList<>();

    private ToolRegistry() {}

    public static void init() {
        TOOLS.clear();
        ChatTools.register(TOOLS);
        InfoTools.register(TOOLS);
        ControlTools.register(TOOLS);
        ScreenTools.register(TOOLS);
        KeyTools.register(TOOLS);
        ModTools.register(TOOLS);
        MemoryTools.register(TOOLS);
        VisionTools.register(TOOLS);
    }

    /** Public extension point for addon mods. */
    public static synchronized void register(Tool tool) {
        TOOLS.removeIf(t -> t.name.equals(tool.name));
        TOOLS.add(tool);
    }

    public static synchronized List<Tool> all() {
        return Collections.unmodifiableList(new ArrayList<>(TOOLS));
    }

    public static synchronized Tool get(String name) {
        for (Tool t : TOOLS) if (t.name.equals(name)) return t;
        return null;
    }
}
