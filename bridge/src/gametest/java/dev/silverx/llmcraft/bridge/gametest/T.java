package dev.silverx.llmcraft.bridge.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.silverx.llmcraft.bridge.Bridge;
import dev.silverx.llmcraft.config.ConfigManager;
import dev.silverx.llmcraft.data.Stores;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolContext;
import dev.silverx.llmcraft.tool.ToolRegistry;
import dev.silverx.llmcraft.tool.ToolResult;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Test helpers shared by the Meteor and JsMacros checks (no Meteor/JsMacros classes here). */
final class T {
    static final String MODEL = System.getenv().getOrDefault("LLMCRAFT_TEST_MODEL", "qwen3.5:4b");

    private T() {}

    /** Runs a tool the way the agent does after approval: deny first, then the handler. */
    static ToolResult run(ClientGameTestContext ctx, String name, String args) {
        JsonObject a = json(args);
        CompletableFuture<ToolResult> f = ctx.computeOnClient(mc -> {
            Tool tool = ToolRegistry.get(name);
            if (tool == null) throw new AssertionError("missing tool " + name);
            ToolContext tc = toolContext();
            String denied = tool.deny.apply(tc, a);
            if (denied != null) return CompletableFuture.completedFuture(ToolResult.error(denied));
            return tool.handler.apply(tc, a);
        });
        ctx.waitFor(mc -> f.isDone(), 20 * 200);
        ToolResult r = f.join();
        Bridge.LOGGER.info("[gametest] {} {} -> {}", name, args, r.text.length() > 400 ? r.text.substring(0, 400) + "…" : r.text);
        return r;
    }

    static String deny(ClientGameTestContext ctx, String name, String args) {
        return ctx.computeOnClient(mc -> ToolRegistry.get(name).deny.apply(toolContext(), json(args)));
    }

    static void check(ClientGameTestContext ctx, String name, String args, String expect) {
        ToolResult r = run(ctx, name, args);
        assertTrue(!r.error, name + " " + args + " returned an error: " + r.text);
        assertTrue(r.text.contains(expect), name + " " + args + " result lacks '" + expect + "': " + r.text);
    }

    static ToolContext toolContext() {
        return new ToolContext(ConfigManager.get(), Stores.current(), ConfigManager.get().activeProfile());
    }

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    static void write(Path p, String content) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
        } catch (Exception e) {
            throw new AssertionError("cannot write " + p, e);
        }
    }

    static void assertTrue(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static boolean ollamaUp() {
        try {
            var r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:11434/api/tags"))
                    .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains(MODEL);
        } catch (Exception e) {
            return false;
        }
    }
}
