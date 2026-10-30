package dev.silverx.llmcraft.config;

/** Permission groups. Every tool belongs to exactly one. */
public enum Category {
    CHAT_READ("Read chat (also puts recent chat into the AI's context)"),
    CHAT_SEND("Send public chat messages"),
    COMMANDS("Run server commands"),
    CLIENT_COMMANDS("Run other mods' client-side commands / chat-prefix commands (Meteor '.', Baritone '#', ...)"),
    WORLD_READ("Read the world: blocks, entities, time, weather, biome"),
    PLAYER_READ("Read player status and inventory"),
    SCREEN("Read and interact with open GUIs/containers"),
    INVENTORY("Change hotbar slot, drop/swap items, open inventory"),
    MOVEMENT("Move and look around"),
    INTERACTION("Attack, use items, mine and place blocks"),
    KEYBINDS("Press keybinds and raw keys (works with any mod's hotkeys)"),
    MODS("See installed mods, their metadata and config files"),
    BARITONE("Control Baritone pathfinding (if installed)"),
    CLIENT_LOG("Read the client log (logs/latest.log)"),
    VISION("Take screenshots for vision-capable models"),
    MEMORY("Write long-term memory and notes");

    public final String description;

    Category(String description) {
        this.description = description;
    }
}
