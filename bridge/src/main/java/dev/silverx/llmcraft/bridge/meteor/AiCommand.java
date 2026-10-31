package dev.silverx.llmcraft.bridge.meteor;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.silverx.llmcraft.agent.Agent;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.commands.SharedSuggestionProvider;

/** .ai <message>: the same as /ai, from Meteor's prefix. */
public final class AiCommand extends Command {
    public AiCommand() {
        super("ai", "Ask the LLMCraft AI (same as /ai).");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        builder.then(argument("message", StringArgumentType.greedyString()).executes(c -> {
            Agent.submit(StringArgumentType.getString(c, "message"), Agent.Source.USER);
            return SINGLE_SUCCESS;
        }));
    }
}
