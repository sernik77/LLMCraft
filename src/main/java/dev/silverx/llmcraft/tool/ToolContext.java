package dev.silverx.llmcraft.tool;

import dev.silverx.llmcraft.config.LlmConfig;
import dev.silverx.llmcraft.data.WorldStore;
import dev.silverx.llmcraft.llm.ProviderProfile;

/** What a tool can see about the request it runs in. */
public final class ToolContext {
    public final LlmConfig config;
    public final WorldStore world;
    public final ProviderProfile profile;
    /** Public chat messages sent during this request (anti-spam budget). */
    public int messagesSent;

    public ToolContext(LlmConfig config, WorldStore world, ProviderProfile profile) {
        this.config = config;
        this.world = world;
        this.profile = profile;
    }
}
