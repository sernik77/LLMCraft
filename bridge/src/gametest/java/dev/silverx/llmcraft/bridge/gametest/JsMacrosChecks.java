package dev.silverx.llmcraft.bridge.gametest;

import com.jsmacrosce.jsmacros.core.config.ScriptTrigger;
import com.jsmacrosce.jsmacros.core.library.impl.FGlobalVars;
import dev.silverx.llmcraft.agent.Agent;
import dev.silverx.llmcraft.api.LlmCraftEvents;
import dev.silverx.llmcraft.bridge.Bridge;
import dev.silverx.llmcraft.bridge.jsmacros.JsMacrosBridge;
import dev.silverx.llmcraft.tool.ToolRegistry;
import dev.silverx.llmcraft.tool.ToolResult;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static dev.silverx.llmcraft.bridge.gametest.T.assertTrue;
import static dev.silverx.llmcraft.bridge.gametest.T.check;
import static dev.silverx.llmcraft.bridge.gametest.T.deny;
import static dev.silverx.llmcraft.bridge.gametest.T.write;

/** Only loaded when JsMacros CE is installed. */
final class JsMacrosChecks {
    private JsMacrosChecks() {}

    static void run(ClientGameTestContext ctx, boolean model) {
        ctx.waitFor(mc -> JsMacrosBridge.ready(), 20 * 30);
        Path dir = ctx.computeOnClient(mc -> JsMacrosBridge.core().config.macroFolder.toPath());
        write(dir.resolve("llmtest_call.js"), """
                Chat.log("script saw " + LLM.getArgs());
                LLM.setResult("hi " + LLM.getArgs() + " ai=" + LLM.isAiCall() + " | " + LLM.runTool("player_status", "{}"));
                """);
        write(dir.resolve("llmtest_fail.js"), "throw new Error('llmtest boom');\n");
        write(dir.resolve("llmtest_loop.js"), "while (true) { Time.sleep(50); }\n");
        write(dir.resolve("llmtest_evt.js"), "GlobalVars.putString('llmtest_evt', event.getString('args'));\n");
        write(dir.resolve("llmtest_toolevt.js"), "GlobalVars.putString('llmtest_tool', event.getString('tool') + ':' + event.getBoolean('error'));\n");
        ctx.runOnClient(mc -> {
            var reg = JsMacrosBridge.core().eventRegistry;
            reg.addScriptTrigger(new ScriptTrigger(ScriptTrigger.TriggerType.EVENT, "LLMTestEvent", "llmtest_evt.js", true, false));
            reg.addScriptTrigger(new ScriptTrigger(ScriptTrigger.TriggerType.EVENT, "LLMTool", "llmtest_toolevt.js", true, false));
        });

        check(ctx, "jsmacros", "{\"action\":\"list\"}", "llmtest_call.js");
        check(ctx, "jsmacros", "{\"action\":\"read\",\"file\":\"llmtest_fail.js\"}", "boom");
        assertTrue(T.run(ctx, "jsmacros", "{\"action\":\"read\",\"file\":\"../../options.txt\"}").error, "path escape must fail");

        // The first run starts GraalJS, which can take a while.
        ToolResult r = T.run(ctx, "jsmacros", "{\"action\":\"run\",\"file\":\"llmtest_call.js\",\"args\":\"abc\",\"timeout_seconds\":120}");
        assertTrue(!r.error && r.text.contains("result: hi abc ai=true") && r.text.contains("health"), "script result missing: " + r.text);
        assertTrue(r.text.contains("script saw abc"), "script chat output missing: " + r.text);

        ToolResult f = T.run(ctx, "jsmacros", "{\"action\":\"run\",\"file\":\"llmtest_fail.js\"}");
        assertTrue(f.error && f.text.contains("llmtest boom"), "script error not reported: " + f.text);

        // Inline code stays off unless bridge.json allows it, and always asks.
        assertTrue(deny(ctx, "jsmacros", "{\"action\":\"code\",\"code\":\"1\"}") != null, "inline code must be off by default");
        assertTrue(ctx.computeOnClient(mc -> ToolRegistry.get("jsmacros").alwaysAsk.apply(null, T.json("{\"action\":\"code\"}"))), "code must always ask");

        // Custom events reach bound macros; built-in ones cannot be faked.
        check(ctx, "jsmacros", "{\"action\":\"event\",\"name\":\"LLMTestEvent\",\"args\":\"ping\"}", "1 macro");
        ctx.waitFor(mc -> "ping".equals(global("llmtest_evt")), 20 * 30);
        assertTrue(deny(ctx, "jsmacros", "{\"action\":\"event\",\"name\":\"Key\"}") != null, "built-in events must not be fired");

        // Disabled macros stop reacting.
        check(ctx, "jsmacros", "{\"action\":\"disable\",\"file\":\"llmtest_evt.js\"}", "disabled 1");
        ctx.runOnClient(mc -> globals().putString("llmtest_evt", "none"));
        T.run(ctx, "jsmacros", "{\"action\":\"event\",\"name\":\"LLMTestEvent\",\"args\":\"again\"}");
        ctx.waitTicks(40);
        assertTrue("none".equals(global("llmtest_evt")), "disabled macro still ran");
        check(ctx, "jsmacros", "{\"action\":\"enable\",\"file\":\"llmtest_evt.js\"}", "enabled 1");

        // Async tool call with a JS callback (the script ends before the callback runs).
        write(dir.resolve("llmtest_async.js"), "LLM.runToolAsync('player_status', '{}', JavaWrapper.methodToJavaAsync((text, isError) => "
                + "GlobalVars.putString('llmtest_async', (isError ? 'ERR ' : 'OK ') + text)));\n");
        check(ctx, "jsmacros", "{\"action\":\"run\",\"file\":\"llmtest_async.js\"}", "finished");
        ctx.waitFor(mc -> {
            String v = global("llmtest_async");
            return v != null && v.startsWith("OK ") && v.contains("health");
        }, 20 * 30);

        // LLMCraft events become JsMacros events.
        ctx.runOnClient(mc -> LlmCraftEvents.toolCalled("craft", "craft torch", "ok", false));
        ctx.waitFor(mc -> "craft:false".equals(global("llmtest_tool")), 20 * 30);

        // The stop key kills scripts the AI started.
        check(ctx, "jsmacros", "{\"action\":\"run\",\"file\":\"llmtest_loop.js\",\"wait\":false}", "started");
        ctx.waitTicks(20);
        check(ctx, "jsmacros", "{\"action\":\"running\"}", "llmtest_loop.js");
        ctx.runOnClient(mc -> Agent.cancel("test"));
        ctx.waitTicks(20);
        String running = T.run(ctx, "jsmacros", "{\"action\":\"running\"}").text;
        assertTrue(!running.contains("llmtest_loop.js"), "stop did not kill the AI's script: " + running);
        Bridge.LOGGER.info("[gametest] JsMacros checks passed");

        if (!model) return;
        // LLM.askAndWait from a script gets the model's reply.
        write(dir.resolve("llmtest_ask.js"), "LLM.setResult('reply=' + LLM.askAndWait('Reply with only the single word: pong. Do not call any tool.'));\n");
        ToolResult ask = T.run(ctx, "jsmacros", "{\"action\":\"run\",\"file\":\"llmtest_ask.js\",\"timeout_seconds\":180}");
        assertTrue(!ask.error && ask.text.toLowerCase().contains("reply=") && ask.text.toLowerCase().contains("pong"), "LLM.askAndWait failed: " + ask.text);

        // LLM.ask with a callback, as in the README.
        write(dir.resolve("llmtest_askcb.js"), "LLM.ask('Reply with only the single word: pong. Do not call any tool.', "
                + "JavaWrapper.methodToJavaAsync((reply, error) => GlobalVars.putString('llmtest_askcb', reply ?? ('ERR ' + error))));\n");
        check(ctx, "jsmacros", "{\"action\":\"run\",\"file\":\"llmtest_askcb.js\"}", "finished");
        ctx.waitFor(mc -> global("llmtest_askcb") != null, 20 * 180);
        assertTrue(global("llmtest_askcb").toLowerCase().contains("pong"), "LLM.ask callback got: " + global("llmtest_askcb"));

        // A script the AI waits for cannot ask the AI back (that would wait forever): it fails fast instead.
        write(dir.resolve("llmtest_deadlock.js"), "LLM.setResult('asked: ' + LLM.askAndWait('hello'));\n");
        StringBuilder toolResult = new StringBuilder();
        LlmCraftEvents.Listener l = new LlmCraftEvents.Listener() {
            @Override
            public void toolCalled(String tool, String summary, String result, boolean error) {
                if (tool.equals("jsmacros") && summary.contains("llmtest_deadlock")) toolResult.append(result);
            }
        };
        LlmCraftEvents.register(l);
        long t0 = System.currentTimeMillis();
        CompletableFuture<String> dl = ctx.computeOnClient(mc -> Agent.request(
                "Use the jsmacros tool once: action run, file llmtest_deadlock.js. Then report its result or error in one sentence.", Agent.Source.USER));
        ctx.waitFor(mc -> dl.isDone(), 20 * 180);
        LlmCraftEvents.unregister(l);
        Bridge.LOGGER.info("[gametest] deadlock-guard: tool said '{}', reply '{}' after {} ms", toolResult, dl.getNow("?"), System.currentTimeMillis() - t0);
        assertTrue(toolResult.toString().contains("the AI is waiting for this script"), "the deadlock guard did not fire: " + toolResult);
        assertTrue(ctx.computeOnClient(mc -> !Agent.busy()), "agent still busy after the deadlock test");
        Bridge.LOGGER.info("[gametest] JsMacros agent checks passed");
    }

    private static FGlobalVars globals() {
        for (var lib : JsMacrosBridge.core().libraryRegistry.libraries.values()) if (lib instanceof FGlobalVars g) return g;
        throw new AssertionError("GlobalVars library missing");
    }

    private static String global(String key) {
        return globals().getString(key);
    }
}
