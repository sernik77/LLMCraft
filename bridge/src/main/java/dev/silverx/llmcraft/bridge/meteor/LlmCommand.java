package dev.silverx.llmcraft.bridge.meteor;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import meteordevelopment.meteorclient.commands.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;

/** .llm [args]: forwards to LLMCraft's /llm (stop, status, perms, profile, ...). */
public final class LlmCommand extends Command {
    public LlmCommand() {
        super("llm", "LLMCraft control, same as /llm: stop, status, perms, profile <name>, triggers on|off, ...");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.executes(c -> run("status"));
        builder.then(argument("args", StringArgumentType.greedyString()).executes(c -> run(StringArgumentType.getString(c, "args"))));
    }

    private int run(String args) {
        var conn = mc.getConnection();
        var dispatcher = ClientCommandManager.getActiveDispatcher();
        if (conn == null || dispatcher == null) {
            error("Join a world first.");
            return SINGLE_SUCCESS;
        }
        try {
            // Fabric gives the client's suggestion provider the client command source interface.
            dispatcher.execute("llm " + args, (FabricClientCommandSource) conn.getSuggestionsProvider());
        } catch (CommandSyntaxException e) {
            error(e.getMessage());
        }
        return SINGLE_SUCCESS;
    }
}
