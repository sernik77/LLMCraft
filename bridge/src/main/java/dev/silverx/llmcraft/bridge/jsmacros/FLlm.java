package dev.silverx.llmcraft.bridge.jsmacros;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jsmacrosce.jsmacros.core.MethodWrapper;
import com.jsmacrosce.jsmacros.core.event.BaseEvent;
import com.jsmacrosce.jsmacros.core.event.impl.EventCustom;
import com.jsmacrosce.jsmacros.core.language.BaseScriptContext;
import com.jsmacrosce.jsmacros.core.library.Library;
import com.jsmacrosce.jsmacros.core.library.PerExecLibrary;
import dev.silverx.llmcraft.agent.Agent;
import dev.silverx.llmcraft.api.LlmCraftApi;
import dev.silverx.llmcraft.bridge.BridgeConfig;
import dev.silverx.llmcraft.tool.ToolResult;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * {@code LLM} in JsMacros scripts.
 * <pre>
 * LLM.submit("mine 10 iron ore")                        // fire and forget, like typing /ai
 * LLM.ask("what should I build?", JavaWrapper.methodToJavaAsync((reply, error) => Chat.log(reply ?? error)))
 * const r = LLM.askAndWait("where is the nearest village?")   // blocks this script (never the game)
 * LLM.runTool("craft", '{"item":"stone_pickaxe"}')      // any LLMCraft tool, without the model
 * if (LLM.isAiCall()) LLM.setResult("done: " + LLM.getArgs())   // scripts the AI runs
 * </pre>
 */
@Library("LLM")
public class FLlm extends PerExecLibrary {
    public FLlm(BaseScriptContext<?> context) {
        super(context);
    }

    /** Sends a request to the AI without waiting (shown in chat like /ai). */
    public void submit(String prompt) {
        guard(prompt);
        LlmCraftApi.ask(prompt, Agent.Source.SCRIPT);
    }

    /** Asks the AI; {@code callback(reply, error)} runs when it is done (reply null on error). */
    public void ask(String prompt, MethodWrapper<String, String, Object, ?> callback) {
        guard(prompt);
        var runner = ctx.runner;
        LlmCraftApi.ask(prompt, Agent.Source.SCRIPT).whenComplete((reply, t) -> runner.threadPool.runTask(() -> {
            if (callback != null) callback.accept(t == null ? reply : null, t == null ? null : message(t));
        }));
    }

    /** Asks the AI and waits for the reply text (blocks this script only). */
    public String askAndWait(String prompt) throws InterruptedException {
        return askAndWait(prompt, BridgeConfig.get().jsmacros.askTimeoutSeconds);
    }

    public String askAndWait(String prompt, int timeoutSeconds) throws InterruptedException {
        guard(prompt);
        noBlocking("askAndWait");
        if (JsMacrosBridge.aiIsWaitingFor(ctx)) {
            throw new IllegalStateException("the AI is waiting for this script, so it cannot answer it now: use LLM.setResult() to hand it a result, or LLM.submit()");
        }
        return await(LlmCraftApi.ask(prompt, Agent.Source.SCRIPT), timeoutSeconds);
    }

    public boolean isBusy() {
        return LlmCraftApi.busy();
    }

    public String status() {
        return LlmCraftApi.status();
    }

    /** Stops the AI (and Baritone, held keys and scripts it started), like the stop key. */
    public void stop() {
        LlmCraftApi.stop("stopped by a script");
    }

    /** LLMCraft's tool names (craft, mine, plan, smelt, use_container, fight, baritone, meteor, ...). */
    public List<String> tools() {
        return LlmCraftApi.toolNames();
    }

    /**
     * Runs an LLMCraft tool directly (no model, no approval prompt; tools set to OFF stay off) and returns its result
     * text. Errors start with "ERROR:". Waits up to 10 minutes; multi-step tools like mine/craft run while you wait.
     */
    public String runTool(String name, String jsonArgs) throws InterruptedException {
        noBlocking("runTool");
        return await(LlmCraftApi.runTool(name, args(jsonArgs)).thenApply(r -> r.text), 600);
    }

    /** Runs an LLMCraft tool; {@code callback(text, isError)} gets the result. */
    public void runToolAsync(String name, String jsonArgs, MethodWrapper<String, Boolean, Object, ?> callback) {
        var runner = ctx.runner;
        CompletableFuture<ToolResult> f = LlmCraftApi.runTool(name, args(jsonArgs));
        f.whenComplete((r, t) -> runner.threadPool.runTask(() -> {
            if (callback == null) return;
            if (t != null) callback.accept("ERROR: " + message(t), true);
            else callback.accept(r.text, r.error);
        }));
    }

    /** True when the AI started this script with its jsmacros tool. */
    public boolean isAiCall() {
        return callEvent() != null;
    }

    /** What the AI passed to this script ("" when it was not started by the AI). */
    public String getArgs() {
        EventCustom e = callEvent();
        String a = e == null ? null : e.getString("args");
        return a == null ? "" : a;
    }

    /** Hands a result back to the AI (it sees it in the tool result). Same as event.putString("result", ...). */
    public void setResult(Object value) {
        EventCustom e = callEvent();
        if (e != null) e.putString("result", String.valueOf(value));
    }

    // -----------------------------------------------------------------------------------------

    private EventCustom callEvent() {
        BaseEvent e = ctx.getTriggeringEvent();
        return e instanceof EventCustom ec && JsMacrosBridge.CALL_EVENT.equals(ec.eventName) ? ec : null;
    }

    private static void guard(String prompt) {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("empty prompt");
    }

    /** Waiting on the render thread would freeze the game: the AI and the tools need it. */
    private static void noBlocking(String what) {
        if (Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("LLM." + what + " cannot wait on the game thread (joined event): use the async variant");
        }
    }

    private static JsonObject args(String json) {
        if (json == null || json.isBlank()) return new JsonObject();
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static <T> T await(CompletableFuture<T> f, int timeoutSeconds) throws InterruptedException {
        try {
            return f.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException(message(e));
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("no answer within " + timeoutSeconds + " s");
        }
    }

    private static String message(Throwable t) {
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
