package dev.silverx.llmcraft.agent;

import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.config.ConfigManager;
import dev.silverx.llmcraft.config.PermissionMode;
import dev.silverx.llmcraft.data.WorldStore;
import dev.silverx.llmcraft.tool.Tool;

/** Effective permission: world override > singleplayer/multiplayer default. */
public final class Permissions {
    private Permissions() {}

    public static PermissionMode mode(Category c, WorldStore world) {
        if (world != null) {
            PermissionMode o = world.settings().permissions.get(c);
            if (o != null) return o;
        }
        var cfg = ConfigManager.get();
        var map = world != null && world.multiplayer ? cfg.multiplayerPermissions : cfg.singleplayerPermissions;
        return map.getOrDefault(c, PermissionMode.ASK);
    }

    public static boolean offered(Tool tool, WorldStore world) {
        if (mode(tool.category, world) != PermissionMode.OFF) return true;
        for (Category c : tool.alsoOfferedUnder) if (mode(c, world) != PermissionMode.OFF) return true;
        return false;
    }
}
