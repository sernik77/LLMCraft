package dev.silverx.llmcraft.data;

import dev.silverx.llmcraft.config.ConfigManager;
import net.minecraft.client.Minecraft;

/** Holds the global memory and the data of the world/server the player is currently in. */
public final class Stores {
    private static MemoryStore global;
    private static WorldStore current;

    private Stores() {}

    public static synchronized MemoryStore global() {
        if (global == null) global = new MemoryStore(ConfigManager.dir().resolve("global_memory.json"));
        return global;
    }

    /** Data for the current world/server, or for the main menu when not in a world. Call on the client thread. */
    public static synchronized WorldStore current() {
        String[] id = identify();
        if (current == null || !current.key.equals(id[0])) current = new WorldStore(id[0], id[1], "mp".equals(id[2]));
        return current;
    }

    public static synchronized void reset() {
        current = null;
        global = null;
    }

    /** [key, display name, sp|mp|menu] */
    static String[] identify() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return new String[]{"menu", "Main menu", "menu"};
        if (mc.isLocalServer() && mc.getSingleplayerServer() != null) {
            var server = mc.getSingleplayerServer();
            String folder = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize().getFileName().toString();
            return new String[]{"sp_" + WorldStore.sanitize(folder), server.getWorldData().getLevelName(), "sp"};
        }
        var data = mc.getCurrentServer();
        if (data != null) {
            String ip = data.ip == null ? "unknown" : data.ip.trim().toLowerCase(java.util.Locale.ROOT);
            if (ip.endsWith(":25565")) ip = ip.substring(0, ip.length() - 6);
            return new String[]{"mp_" + WorldStore.sanitize(ip), data.name + " (" + data.ip + ")", "mp"};
        }
        return new String[]{"mp_unknown", "Unknown server", "mp"};
    }
}
