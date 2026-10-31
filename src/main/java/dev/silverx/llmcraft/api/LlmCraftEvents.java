package dev.silverx.llmcraft.api;

import dev.silverx.llmcraft.LlmCraft;
import dev.silverx.llmcraft.agent.Agent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the agent is doing, for addon mods (script mods, HUDs, other clients). Listeners run on whatever thread the
 * agent is on (often the client thread): return quickly and hand longer work to your own thread.
 */
public final class LlmCraftEvents {
    public interface Listener {
        /** A request begins (after it left the queue). */
        default void requestStarted(String prompt, Agent.Source source) {}

        /** A tool finished (or was refused/denied: then {@code error} is true). */
        default void toolCalled(String tool, String summary, String result, boolean error) {}

        /** Reply text shown to the player (one call per model message, may have several lines). */
        default void reply(String text) {}

        /** A request ended. {@code outcome}: done, error, cancelled. {@code reply}: all text shown during it. */
        default void requestFinished(String prompt, Agent.Source source, String reply, String outcome) {}

        /** Everything was stopped (/llm stop, the stop key, disconnect, ...). Stop whatever you started for the AI. */
        default void cancelled(String reason) {}
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private LlmCraftEvents() {}

    public static void register(Listener l) {
        LISTENERS.add(l);
    }

    public static void unregister(Listener l) {
        LISTENERS.remove(l);
    }

    public static void requestStarted(String prompt, Agent.Source source) {
        each(l -> l.requestStarted(prompt, source));
    }

    public static void toolCalled(String tool, String summary, String result, boolean error) {
        each(l -> l.toolCalled(tool, summary, result, error));
    }

    public static void reply(String text) {
        each(l -> l.reply(text));
    }

    public static void requestFinished(String prompt, Agent.Source source, String reply, String outcome) {
        each(l -> l.requestFinished(prompt, source, reply, outcome));
    }

    public static void cancelled(String reason) {
        each(l -> l.cancelled(reason));
    }

    private static void each(java.util.function.Consumer<Listener> call) {
        for (Listener l : LISTENERS) {
            try {
                call.accept(l);
            } catch (Throwable t) {
                LlmCraft.LOGGER.warn("LLMCraft event listener failed", t);
            }
        }
    }
}
