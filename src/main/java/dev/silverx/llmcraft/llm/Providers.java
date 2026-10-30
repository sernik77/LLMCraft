package dev.silverx.llmcraft.llm;

/** Builds the right client for a profile. */
public final class Providers {
    private Providers() {}

    public static LlmProvider create(ProviderProfile profile) {
        LlmProvider base = switch (profile.type) {
            case OPENAI -> new OpenAiProvider(profile);
            case OLLAMA -> new OllamaProvider(profile);
            case ANTHROPIC -> new AnthropicProvider(profile);
        };
        return profile.toolMode == ProviderProfile.ToolMode.JSON ? new JsonToolAdapter(base) : base;
    }
}
