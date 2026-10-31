package dev.silverx.llmcraft.api;

import com.google.gson.JsonObject;
import dev.silverx.llmcraft.agent.Agent;
import dev.silverx.llmcraft.config.ConfigManager;
import dev.silverx.llmcraft.data.Stores;
import dev.silverx.llmcraft.game.ClientThread;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolContext;
import dev.silverx.llmcraft.tool.ToolRegistry;
import dev.silverx.llmcraft.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Entry points for addon mods and scripts. Tools themselves are added with {@link ToolRegistry#register(Tool)}. */
public final class LlmCraftApi {
    private LlmCraftApi() {}

    /** Asks the AI. Completes with the reply text shown to the player; fails if it could not run or was cancelled. */
    public static CompletableFuture<String> ask(String prompt, Agent.Source source) {
        return Agent.request(prompt, source);
    }

    public static boolean busy() {
        return Agent.busy();
    }

    public static String status() {
        return Agent.status();
    }

    public static void stop(String reason) {
        Agent.cancel(reason);
    }

    /** Names of all registered tools (whether the model is offered them depends on permissions). */
    public static List<String> toolNames() {
        List<String> out = new ArrayList<>();
        for (Tool t : ToolRegistry.all()) out.add(t.name);
        return out;
    }

    /**
     * Runs one tool directly, without the model and without approval prompts: for the player's own scripts. The tool's
     * own refusals ({@code deny}) still apply, and categories set to OFF stay off.
     */
    public static CompletableFuture<ToolResult> runTool(String name, JsonObject args) {
        Tool tool = ToolRegistry.get(name);
        if (tool == null) return CompletableFuture.completedFuture(ToolResult.error("no tool '" + name + "'"));
        JsonObject a = args == null ? new JsonObject() : args;
        return ClientThread.call(() -> {
            var cfg = ConfigManager.get();
            var world = Stores.current();
            ToolContext ctx = new ToolContext(cfg, world, cfg.activeProfile());
            if (!tool.available.test(ctx)) return CompletableFuture.completedFuture(ToolResult.error("tool '" + name + "' is not available right now"));
            if (dev.silverx.llmcraft.agent.Permissions.mode(tool.categoryFor(ctx, a), world) == dev.silverx.llmcraft.config.PermissionMode.OFF) {
                return CompletableFuture.completedFuture(ToolResult.error("the category of '" + name + "' is OFF"));
            }
            String denied = tool.deny.apply(ctx, a);
            if (denied != null) return CompletableFuture.completedFuture(ToolResult.error(denied));
            return tool.handler.apply(ctx, a);
        }).thenCompose(f -> f);
    }
}
