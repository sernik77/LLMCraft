package dev.silverx.llmcraft.data;

import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.config.PermissionMode;

import java.util.EnumMap;
import java.util.Map;

/** Per world/server overrides, stored in worlds/<key>/settings.json. */
public final class WorldSettings {
    /** Disable the AI entirely for this world/server. */
    public boolean enabled = true;
    /** Use a different provider profile here (empty = global active profile). */
    public String profile = "";
    /** Overrides the global singleplayer/multiplayer permission for a category. */
    public Map<Category, PermissionMode> permissions = new EnumMap<>(Category.class);
    /** Overrides global triggers.enabled here (null = inherit). */
    public Boolean triggersEnabled = null;
    /** Display name shown in /llm status (informational). */
    public String label = "";
}
