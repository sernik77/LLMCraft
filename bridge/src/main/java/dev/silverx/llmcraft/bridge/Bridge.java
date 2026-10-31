package dev.silverx.llmcraft.bridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. Meteor and JsMacros code lives in its own packages and is only reached after an isModLoaded
 * check, so the bridge loads with either mod, both, or neither. Meteor's own side starts from the "meteor" entrypoint.
 */
public final class Bridge implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("LLMCraft Bridge");

    @Override
    public void onInitializeClient() {
        BridgeConfig.load();
        if (meteor()) dev.silverx.llmcraft.bridge.meteor.MeteorTool.register();
        if (jsMacros()) {
            dev.silverx.llmcraft.bridge.jsmacros.JsMacrosTool.register();
            // JsMacros builds its core in its own entrypoint; the order of entrypoints is not guaranteed.
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> dev.silverx.llmcraft.bridge.jsmacros.JsMacrosBridge.init());
        }
        LOGGER.info("LLMCraft Bridge ready (Meteor: {}, JsMacros: {})", meteor(), jsMacros());
    }

    public static boolean meteor() {
        return FabricLoader.getInstance().isModLoaded("meteor-client");
    }

    public static boolean jsMacros() {
        return FabricLoader.getInstance().isModLoaded("jsmacrosce");
    }
}
