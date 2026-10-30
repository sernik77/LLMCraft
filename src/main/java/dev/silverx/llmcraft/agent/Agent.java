package dev.silverx.llmcraft.agent;

import com.google.gson.JsonObject;
import dev.silverx.llmcraft.LlmCraft;
import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.config.ConfigManager;
import dev.silverx.llmcraft.config.LlmConfig;
import dev.silverx.llmcraft.config.PermissionMode;
import dev.silverx.llmcraft.data.HistoryEntry;
import dev.silverx.llmcraft.data.Stores;
import dev.silverx.llmcraft.data.WorldStore;
import dev.silverx.llmcraft.game.BaritoneBridge;
import dev.silverx.llmcraft.game.ClientThread;
import dev.silverx.llmcraft.game.Controller;
import dev.silverx.llmcraft.game.Output;
import dev.silverx.llmcraft.llm.ChatRequest;
import dev.silverx.llmcraft.llm.ChatResponse;
import dev.silverx.llmcraft.llm.LlmProvider;
import dev.silverx.llmcraft.llm.Message;
import dev.silverx.llmcraft.llm.ProviderProfile;
import dev.silverx.llmcraft.llm.Providers;
import dev.silverx.llmcraft.llm.ToolCall;
import dev.silverx.llmcraft.llm.ToolSpec;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolContext;
import dev.silverx.llmcraft.tool.ToolRegistry;
import dev.silverx.llmcraft.tool.ToolResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** Runs requests: prompt -> model -> tools -> model ... -> reply. One request at a time, others queue. */
public final class Agent {
    public enum Source { USER, TRIGGER }

    private record Queued(String text, Source source) {}

    private static final Deque<Queued> QUEUE = new ArrayDeque<>();
    private static final int MAX_QUEUE = 5;
    private static Job current;

    private Agent() {}

    public static synchronized boolean busy() {
        return current != null;
    }

    public static synchronized void submit(String text, Source source) {
        if (text == null || text.isBlank()) return;
        LlmConfig cfg = ConfigManager.get();
        if (!cfg.enabled) {
            if (source == Source.USER) Output.error("LLMCraft is disabled (/llm enable).");
            return;
        }
        if (current != null) {
            if (QUEUE.size() >= MAX_QUEUE) {
                if (source == Source.USER) Output.error("Busy and the queue is full. Use /llm stop.");
                return;
            }
            QUEUE.addLast(new Queued(text, source));
            if (source == Source.USER) Output.info("Queued (" + QUEUE.size() + " waiting).");
            return;
        }
        start(text, source);
    }

    private static void start(String text, Source source) {
        Job job = new Job(text, source);
        current = job;
        ClientThread.run(job::begin);
    }

    private static synchronized void finished(Job job) {
        if (current != job) return;
        current = null;
        Queued next = QUEUE.pollFirst();
        if (next != null) start(next.text, next.source);
    }

    /** Stops the running request, the queue, pending approvals and every AI-held key/task. */
    public static void cancel(String reason) {
        Job job;
        synchronized (Agent.class) {
            QUEUE.clear();
            job = current;
            current = null;
        }
        Approvals.denyAll();
        if (job != null) job.cancelled = true;
        ClientThread.run(() -> {
            Controller.stopAll(reason);
            if (job != null && job.usedBaritone) BaritoneBridge.cancel();
        });
    }

    public static synchronized String status() {
        if (current == null) return "idle" + (QUEUE.isEmpty() ? "" : ", " + QUEUE.size() + " queued");
        return "working (step " + current.step + ", " + (System.currentTimeMillis() - current.startedAt) / 1000 + "s)"
                + (QUEUE.isEmpty() ? "" : ", " + QUEUE.size() + " queued");
    }

    // ------------------------------------------------------------------------------------------

    private static final class Job {
        final String input;
        final Source source;
        final long startedAt = System.currentTimeMillis();
        volatile boolean cancelled;
        volatile boolean usedBaritone;
        int step;
        int toolChars;
        LlmConfig cfg;
        WorldStore world;
        ProviderProfile profile;
        LlmProvider provider;
        ToolContext toolCtx;
        final ChatRequest request = new ChatRequest();
        final List<String> actionLog = new ArrayList<>();
        final StringBuilder shownText = new StringBuilder();

        Job(String input, Source source) {
            this.input = input;
            this.source = source;
        }

        /** Client thread: snapshot everything the request needs. */
        void begin() {
            try {
                cfg = ConfigManager.get();
                world = Stores.current();
                if (!world.settings().enabled) {
                    if (source == Source.USER) Output.error("The AI is disabled for this world/server (/llm world enable).");
                    finished(this);
                    return;
                }
                String profileName = world.settings().profile;
                profile = profileName != null && !profileName.isBlank() ? cfg.profile(profileName) : cfg.activeProfile();
                if (profile == null) {
                    Output.error("No provider profile configured. Edit config/llmcraft/config.json.");
                    finished(this);
                    return;
                }
                provider = Providers.create(profile);
                toolCtx = new ToolContext(cfg, world, profile);

                String system = PromptBuilder.systemPrompt(cfg, world);
                if (profile.toolMode != ProviderProfile.ToolMode.NONE) {
                    for (Tool t : ToolRegistry.all()) {
                        if (Permissions.offered(t, world) && t.available.test(toolCtx)) request.tools.add(t.spec());
                    }
                }
                toolChars = toolChars(request.tools);
                request.messages.add(Message.system(system));
                request.messages.addAll(PromptBuilder.history(world, profile.contextTokens, system.length(), toolChars, profile.maxTokens));
                request.messages.add(Message.user(input));
                int needed = (system.length() + toolChars + input.length()) / PromptBuilder.CHARS_PER_TOKEN;
                if (needed > profile.contextTokens * 0.9 && !warnedContext) {
                    warnedContext = true;
                    Output.error("Prompt (~" + needed + " tokens incl. " + request.tools.size() + " tools) is close to the context window of '"
                            + profile.name + "' (" + profile.contextTokens + "). Raise contextTokens or turn off unused permission categories.");
                }
                if (source == Source.USER) Output.info("thinking… (" + profile.name + ")");
                loop().orTimeout(cfg.agent.requestTimeoutSeconds, TimeUnit.SECONDS)
                        .whenComplete((v, t) -> {
                            if (t != null && !cancelled) {
                                Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
                                LlmCraft.LOGGER.warn("Request failed", c);
                                String msg = c instanceof java.util.concurrent.TimeoutException ? "request timed out" : c.getMessage();
                                Output.error("Error: " + (msg == null ? c.getClass().getSimpleName() : msg));
                            }
                            finished(this);
                        });
            } catch (Throwable t) {
                LlmCraft.LOGGER.error("Could not start request", t);
                Output.error("Could not start: " + t.getMessage());
                finished(this);
            }
        }

        CompletableFuture<Void> loop() {
            if (cancelled) return CompletableFuture.completedFuture(null);
            step++;
            fitToContext();
            return provider.chat(request).thenCompose(resp -> {
                if (resp.promptTokens >= 0) lastPromptTokens = resp.promptTokens;
                if (cancelled) return CompletableFuture.completedFuture(null);
                showResponse(resp);
                request.messages.add(resp.toAssistantMessage());
                if (!resp.hasToolCalls()) {
                    complete(resp.text);
                    return CompletableFuture.completedFuture(null);
                }
                if (step >= cfg.agent.maxSteps) {
                    Output.error("Stopped after " + step + " steps (agent.maxSteps).");
                    complete(resp.text);
                    return CompletableFuture.completedFuture(null);
                }
                List<ToolCall> calls = resp.toolCalls;
                if (calls.size() > cfg.agent.maxToolCallsPerStep) calls = calls.subList(0, cfg.agent.maxToolCallsPerStep);
                CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                for (ToolCall call : calls) {
                    chain = chain.thenCompose(v -> cancelled ? CompletableFuture.completedFuture(null) : execute(call).thenAccept(result -> {
                        String text = result.text;
                        int max = cfg.agent.maxToolResultChars;
                        if (text.length() > max) text = text.substring(0, max) + "\n…(truncated, " + result.text.length() + " chars)";
                        Message m = Message.toolResult(call, text);
                        if (profile.vision) m.images.addAll(result.images);
                        request.messages.add(m);
                    }));
                }
                // Tool calls the model sent beyond the cap still need an answer for strict providers.
                for (ToolCall skipped : resp.toolCalls.subList(calls.size(), resp.toolCalls.size())) {
                    chain = chain.thenRun(() -> request.messages.add(Message.toolResult(skipped, "ERROR: skipped, too many tool calls in one step")));
                }
                return chain.thenCompose(v -> loop());
            });
        }

        CompletableFuture<ToolResult> execute(ToolCall call) {
            Tool tool = ToolRegistry.get(call.name());
            JsonObject args = call.arguments() == null ? new JsonObject() : call.arguments();
            if (tool == null || !Permissions.offered(tool, world)) {
                return CompletableFuture.completedFuture(ToolResult.error("unknown or unavailable tool '" + call.name() + "'"));
            }
            return ClientThread.call(() -> gate(tool, args)).thenCompose(gate -> {
                if (gate.error != null) {
                    actionLog.add(tool.name + " (refused)");
                    return CompletableFuture.completedFuture(ToolResult.error(gate.error));
                }
                CompletableFuture<Boolean> approved = gate.ask
                        ? Approvals.request(gate.category, gate.summary, cfg.approvalTimeoutSeconds)
                        : CompletableFuture.completedFuture(true);
                return approved.thenCompose(ok -> {
                    if (cancelled) return CompletableFuture.completedFuture(ToolResult.error("request cancelled"));
                    if (!ok) {
                        actionLog.add(gate.summary + " (denied)");
                        Output.tool("denied: " + gate.summary);
                        return CompletableFuture.completedFuture(ToolResult.error("the player denied this action"));
                    }
                    if (cfg.chat.showToolCalls) Output.tool(gate.summary);
                    if (tool.category == Category.BARITONE) usedBaritone = true;
                    CompletableFuture<ToolResult> run;
                    try {
                        run = tool.handler.apply(toolCtx, args);
                    } catch (Throwable t) {
                        run = CompletableFuture.failedFuture(t);
                    }
                    return run.handle((r, t) -> {
                        if (t != null) {
                            Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
                            LlmCraft.LOGGER.debug("Tool {} failed", tool.name, c);
                            r = ToolResult.error(c.getMessage() == null ? c.toString() : c.getMessage());
                        }
                        actionLog.add(gate.summary + (r.error ? " -> " + abbreviate(r.text, 80) : ""));
                        return r;
                    });
                });
            });
        }

        /** Shrinks old tool results (and drops old screenshots) when the conversation outgrows the context window. */
        void fitToContext() {
            int limit = profile.contextTokens * PromptBuilder.CHARS_PER_TOKEN - toolChars - Math.max(1024, profile.maxTokens) * PromptBuilder.CHARS_PER_TOKEN;
            int total = 0;
            for (Message m : request.messages) total += m.textOrEmpty().length();
            int lastTool = -1;
            for (int i = 0; i < request.messages.size(); i++) if (request.messages.get(i).role == Message.Role.TOOL) lastTool = i;
            for (int i = 0; i < request.messages.size() && total > limit; i++) {
                Message m = request.messages.get(i);
                if (m.role != Message.Role.TOOL || i == lastTool || m.textOrEmpty().length() <= 200) continue;
                total -= m.text.length() - 120;
                m.text = m.text.substring(0, 100) + "… (older result shortened to fit the context window)";
                m.images.clear();
            }
        }

        private record Gate(String error, boolean ask, Category category, String summary) {}

        private Gate gate(Tool tool, JsonObject args) {
            Category cat = tool.categoryFor(toolCtx, args);
            String summary = tool.summarize(args);
            PermissionMode mode = Permissions.mode(cat, world);
            if (mode == PermissionMode.OFF) return new Gate("the player disabled '" + cat.name().toLowerCase() + "'", false, cat, summary);
            String denied;
            try {
                denied = tool.deny.apply(toolCtx, args);
            } catch (Exception e) {
                denied = e.getMessage();
            }
            if (denied != null) return new Gate(denied, false, cat, summary);
            boolean ask = mode == PermissionMode.ASK && !Approvals.sessionAllowed(cat) && !Boolean.TRUE.equals(tool.autoAllow.apply(toolCtx, args));
            return new Gate(null, ask, cat, summary);
        }

        void showResponse(ChatResponse resp) {
            if (cfg.chat.showThinking && resp.thinking != null && !resp.thinking.isBlank()) Output.thinking(abbreviate(resp.thinking.trim(), 400));
            String text = resp.text == null ? "" : resp.text.trim();
            if (!text.isEmpty() && !isSilent(text)) {
                for (String line : text.split("\n+")) if (!line.isBlank()) Output.reply(line.trim());
                if (!shownText.isEmpty()) shownText.append('\n');
                shownText.append(text);
            }
            if (cfg.chat.showTokenUsage && resp.promptTokens >= 0) Output.info("tokens: " + resp.promptTokens + " in / " + resp.completionTokens + " out");
        }

        void complete(String finalText) {
            if (cancelled) return;
            String userText = source == Source.USER ? input : "[automatic trigger] " + input;
            String assistant = shownText.toString().trim();
            if (cfg.agent.keepToolSummariesInHistory && !actionLog.isEmpty()) {
                assistant = (assistant + "\n[actions: " + abbreviate(String.join("; ", actionLog), 700) + "]").trim();
            }
            if (source == Source.TRIGGER && assistant.isEmpty()) return; // nothing happened, keep history clean
            world.addHistory(new HistoryEntry("user", userText));
            world.addHistory(new HistoryEntry("assistant", assistant.isEmpty() ? "(no reply)" : assistant));
            History.maybeCompact(world, provider, cfg);
        }

        static boolean isSilent(String text) {
            String t = text.trim().toUpperCase();
            return t.equals("NOTHING") || t.equals("NOTHING.") || t.equals("[NOTHING]");
        }
    }

    private static boolean warnedContext;
    private static volatile int lastPromptTokens = -1;

    /** Prompt size reported by the provider for the most recent model call (-1 if unknown). */
    public static int lastPromptTokens() {
        return lastPromptTokens;
    }

    static int toolChars(List<ToolSpec> tools) {
        int n = 0;
        for (ToolSpec t : tools) n += t.name().length() + t.description().length() + t.parameters().toString().length() + 40;
        return n;
    }

    static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public static List<ToolSpec> offeredTools() {
        WorldStore world = Stores.current();
        ProviderProfile p = ConfigManager.get().activeProfile();
        ToolContext ctx = new ToolContext(ConfigManager.get(), world, p);
        List<ToolSpec> out = new ArrayList<>();
        for (Tool t : ToolRegistry.all()) if (Permissions.offered(t, world) && t.available.test(ctx)) out.add(t.spec());
        return out;
    }
}
