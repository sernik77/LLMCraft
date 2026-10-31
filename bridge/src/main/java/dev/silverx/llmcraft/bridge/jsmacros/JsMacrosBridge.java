package dev.silverx.llmcraft.bridge.jsmacros;

import com.jsmacrosce.jsmacros.client.JsMacrosClient;
import com.jsmacrosce.jsmacros.core.Core;
import com.jsmacrosce.jsmacros.core.config.ScriptTrigger;
import com.jsmacrosce.jsmacros.core.event.BaseEvent;
import com.jsmacrosce.jsmacros.core.event.impl.EventCustom;
import com.jsmacrosce.jsmacros.core.language.BaseScriptContext;
import com.jsmacrosce.jsmacros.core.language.BaseWrappedException;
import com.jsmacrosce.jsmacros.core.language.EventContainer;
import dev.silverx.llmcraft.agent.Agent;
import dev.silverx.llmcraft.api.LlmCraftEvents;
import dev.silverx.llmcraft.bridge.Bridge;
import dev.silverx.llmcraft.bridge.BridgeConfig;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** JsMacros CE side: the LLM library, the LLM* events, and running scripts for the AI. */
public final class JsMacrosBridge {
    /** Event handed to scripts the AI runs (event.getString("args"), event.putString("result", ...)). */
    public static final String CALL_EVENT = "LLMCall";
    /** Events macros can be bound to in the JsMacros GUI. */
    public static final List<String> EVENTS = List.of(CALL_EVENT, "LLMRequest", "LLMReply", "LLMTool", "LLMDone", "LLMStop");

    /** Scripts started by the AI: stopped with everything else on /llm stop or the stop key. */
    static final Set<BaseScriptContext<?>> AI_RUNS = ConcurrentHashMap.newKeySet();
    private static volatile boolean ready;

    private JsMacrosBridge() {}

    public static Core<?, ?> core() {
        return JsMacrosClient.clientCore;
    }

    public static boolean ready() {
        return ready;
    }

    /** After the client started, when JsMacros has built its core. */
    public static synchronized void init() {
        if (ready) return;
        Core<?, ?> core = core();
        core.libraryRegistry.addLibrary(FLlm.class);
        for (String e : EVENTS) core.eventRegistry.addEvent(e);
        LlmCraftEvents.register(new LlmCraftEvents.Listener() {
            @Override
            public void requestStarted(String prompt, Agent.Source source) {
                fire("LLMRequest", Map.of("prompt", prompt, "source", source.name()));
            }

            @Override
            public void toolCalled(String tool, String summary, String result, boolean error) {
                fire("LLMTool", Map.of("tool", tool, "summary", summary, "result", result, "error", error));
            }

            @Override
            public void reply(String text) {
                fire("LLMReply", Map.of("text", text));
            }

            @Override
            public void requestFinished(String prompt, Agent.Source source, String reply, String outcome) {
                fire("LLMDone", Map.of("prompt", prompt, "source", source.name(), "reply", reply, "outcome", outcome));
            }

            @Override
            public void cancelled(String reason) {
                stopAiRuns();
                fire("LLMStop", Map.of("reason", reason));
            }
        });
        ready = true;
        Bridge.LOGGER.info("JsMacros integration ready: LLM library, events {}", EVENTS);
    }

    /** Fires a custom event for macros bound to it (off the client thread). */
    static void fire(String name, Map<String, Object> values) {
        if (!ready || !BridgeConfig.get().jsmacros.fireEvents) return;
        Core<?, ?> core = core();
        if (core.eventRegistry.getListeners(name).isEmpty() && core.eventRegistry.getListeners("ANYTHING").isEmpty()) return;
        core.threadPool.runTask(() -> {
            try {
                EventCustom e = new EventCustom(core, name);
                values.forEach((k, v) -> {
                    if (v instanceof Boolean b) e.putBoolean(k, b);
                    else e.putString(k, String.valueOf(v));
                });
                e.trigger();
            } catch (Throwable t) {
                Bridge.LOGGER.warn("JsMacros event {} failed", name, t);
            }
        });
    }

    public record Run(BaseScriptContext<?> context, EventCustom event, CompletableFuture<String> finished) {}

    /**
     * Starts a script for the AI with an LLMCall event. {@code finished} completes with null when the script's main
     * body ended, or with the error text.
     */
    static Run start(Path file, String code, String language, String args, boolean aiWaits) {
        Core<?, ?> core = core();
        EventCustom ev = new EventCustom(core, CALL_EVENT);
        ev.putString("args", args == null ? "" : args);
        ev.putBoolean("aiWaiting", aiWaits);
        CompletableFuture<String> finished = new CompletableFuture<>();
        Runnable then = () -> finished.complete(null);
        java.util.function.Consumer<Throwable> catcher = t -> finished.complete(errorText(core, t));
        EventContainer<?> c;
        if (code != null) {
            String ext = language == null || language.isBlank() ? "js" : language.replaceFirst("^\\.", "");
            File fake = new File(core.config.macroFolder, "llmcraft-inline." + ext);
            ev.putString("file", fake.getName());
            c = core.exec(ext, code, fake, ev, then, catcher);
        } else {
            ev.putString("file", core.config.macroFolder.toPath().relativize(file).toString());
            c = core.exec(new ScriptTrigger(ScriptTrigger.TriggerType.EVENT, CALL_EVENT, file, true, false), ev, then, catcher);
        }
        BaseScriptContext<?> ctx = c.getCtx();
        AI_RUNS.add(ctx);
        finished.whenComplete((r, t) -> {
            ev.putBoolean("aiWaiting", false);
            // Scripts that keep listeners alive stay "running" for /llm stop until they close themselves.
            if (ctx.isContextClosed() || !ctx.shouldKeepAlive()) AI_RUNS.remove(ctx);
        });
        return new Run(ctx, ev, finished);
    }

    static String errorText(Core<?, ?> core, Throwable t) {
        try {
            BaseWrappedException<?> w = core.wrapException(t);
            if (w != null && w.message != null) {
                StringBuilder sb = new StringBuilder(w.message);
                if (w.location != null) sb.append(" (at ").append(w.location).append(')');
                return sb.toString();
            }
        } catch (Throwable ignored) {
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    /** Stops every script the AI started that is still running. */
    public static int stopAiRuns() {
        int n = 0;
        for (BaseScriptContext<?> ctx : List.copyOf(AI_RUNS)) {
            AI_RUNS.remove(ctx);
            if (!ctx.isContextClosed()) {
                ctx.closeContext();
                n++;
            }
        }
        return n;
    }

    /** True when this script was started by the AI's jsmacros tool, which is now waiting for it to finish. */
    static boolean aiIsWaitingFor(BaseScriptContext<?> ctx) {
        BaseEvent e = ctx == null ? null : ctx.getTriggeringEvent();
        return e instanceof EventCustom ec && CALL_EVENT.equals(ec.eventName) && Boolean.TRUE.equals(ec.getBoolean("aiWaiting")) && Agent.busy();
    }
}
