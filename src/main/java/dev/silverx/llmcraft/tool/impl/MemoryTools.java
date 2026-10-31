package dev.silverx.llmcraft.tool.impl;

import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.data.MemoryStore;
import dev.silverx.llmcraft.data.Stores;
import dev.silverx.llmcraft.tool.Args;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolContext;
import dev.silverx.llmcraft.tool.ToolResult;

import java.util.List;

public final class MemoryTools {
    private MemoryTools() {}

    private static MemoryStore store(ToolContext ctx, String scope) {
        return "global".equalsIgnoreCase(scope) ? Stores.global() : ctx.world.memory;
    }

    public static void register(List<Tool> out) {
        out.add(Tool.builder("task_notes", Category.PLAYER_READ,
                        "Your scratchpad for the current request: write what you know and still need (goal, key coordinates, what is left, what failed). "
                                + "It is shown to you in every following step and replaces the previous notes - keep it short.")
                .param("text", "string", "The whole notes text (replaces the old notes)", true)
                .summary(a -> "task_notes: " + Args.str(a, "text", ""))
                .sync((ctx, a) -> {
                    String err = dev.silverx.llmcraft.agent.Agent.setNotes(Args.reqStr(a, "text"));
                    return err == null ? ToolResult.ok("notes saved") : ToolResult.error(err);
                }).build());

        out.add(Tool.builder("remember", Category.MEMORY,
                        "Save a fact to long-term memory. scope 'world' = only this world/server (bases, coordinates, players, goals); 'global' = everywhere (player preferences). Keep facts short and specific.")
                .param("text", "string", "The fact", true)
                .enumParam("scope", "Default world", false, "world", "global")
                .param("tag", "string", "Optional short tag, e.g. 'base', 'todo', 'player'", false)
                .summary(a -> "remember (" + Args.str(a, "scope", "world") + "): " + Args.str(a, "text", ""))
                .sync((ctx, a) -> {
                    var e = store(ctx, Args.str(a, "scope", "world")).add(Args.reqStr(a, "text"), Args.str(a, "tag", ""));
                    return ToolResult.ok("saved as #" + e.id);
                }).build());

        out.add(Tool.builder("update_memory", Category.MEMORY, "Replace the text of a memory entry.")
                .param("id", "integer", "Entry id", true).param("text", "string", "New text", true)
                .enumParam("scope", "Default world", false, "world", "global")
                .sync((ctx, a) -> store(ctx, Args.str(a, "scope", "world")).update(Args.integer(a, "id", -1), Args.reqStr(a, "text"))
                        ? ToolResult.ok("updated") : ToolResult.error("no such entry")).build());

        out.add(Tool.builder("forget", Category.MEMORY, "Delete a memory entry by id (when it is wrong or outdated).")
                .param("id", "integer", "Entry id", true)
                .enumParam("scope", "Default world", false, "world", "global")
                .sync((ctx, a) -> store(ctx, Args.str(a, "scope", "world")).remove(Args.integer(a, "id", -1))
                        ? ToolResult.ok("deleted") : ToolResult.error("no such entry")).build());

        out.add(Tool.builder("list_memory", Category.MEMORY, "Show all memory entries of a scope.")
                .enumParam("scope", "Default world", false, "world", "global")
                .sync((ctx, a) -> {
                    String s = store(ctx, Args.str(a, "scope", "world")).render();
                    return ToolResult.ok(s.isEmpty() ? "(empty)" : s);
                }).build());
    }
}
