package dev.silverx.llmcraft.agent;

import com.google.gson.JsonObject;
import dev.silverx.llmcraft.LlmCraft;
import dev.silverx.llmcraft.api.LlmCraftEvents;
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
import dev.silverx.llmcraft.game.Inv;
import dev.silverx.llmcraft.game.Output;
import dev.silverx.llmcraft.game.Safety;
import dev.silverx.llmcraft.game.Snapshot;
import net.minecraft.client.Minecraft;
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
    /** USER: typed by the player. TRIGGER: automatic. SCRIPT: another mod/script (an addon API call). Only USER may order attacks on players. */
    public enum Source { USER, TRIGGER, SCRIPT }

    private record Queued(String text, Source source, CompletableFuture<String> result) {}

    private static final Deque<Queued> QUEUE = new ArrayDeque<>();
    private static final int MAX_QUEUE = 5;
    private static Job current;

    private Agent() {}

    public static synchronized boolean busy() {
        return current != null;
    }

    public static void submit(String text, Source source) {
        request(text, source);
    }

    /**
     * Queues a request. The future completes with all reply text shown to the player (empty if none) once the request
     * is done; it fails when the request could not run, failed, or was cancelled.
     */
    public static synchronized CompletableFuture<String> request(String text, Source source) {
        CompletableFuture<String> result = new CompletableFuture<>();
        if (text == null || text.isBlank()) {
            result.completeExceptionally(new IllegalArgumentException("empty request"));
            return result;
        }
        LlmConfig cfg = ConfigManager.get();
        if (!cfg.enabled) {
            if (source == Source.USER) Output.error("LLMCraft is disabled (/llm enable).");
            result.completeExceptionally(new IllegalStateException("LLMCraft is disabled"));
            return result;
        }
        if (current != null) {
            if (QUEUE.size() >= MAX_QUEUE) {
                if (source == Source.USER) Output.error("Busy and the queue is full. Use /llm stop.");
                result.completeExceptionally(new IllegalStateException("busy and the queue is full"));
                return result;
            }
            QUEUE.addLast(new Queued(text, source, result));
            if (source == Source.USER) Output.info("Queued (" + QUEUE.size() + " waiting).");
            return result;
        }
        start(new Queued(text, source, result));
        return result;
    }

    private static void start(Queued q) {
        Job job = new Job(q.text, q.source, q.result);
        current = job;
        ClientThread.run(job::begin);
    }

    private static void finished(Job job) {
        synchronized (Agent.class) {
            if (current == job) {
                current = null;
                Queued next = QUEUE.pollFirst();
                if (next != null) start(next);
            }
        }
        job.settle();
    }

    /** Stops the running request, the queue, pending approvals and every AI-held key/task. */
    public static void cancel(String reason) {
        Job job;
        List<Queued> dropped;
        synchronized (Agent.class) {
            dropped = new ArrayList<>(QUEUE);
            QUEUE.clear();
            job = current;
            current = null;
        }
        for (Queued q : dropped) q.result.completeExceptionally(new java.util.concurrent.CancellationException(reason));
        Approvals.denyAll();
        if (job != null) {
            job.cancelled = true;
            job.settle();
        }
        ClientThread.run(() -> {
            Controller.stopAll(reason);
            if (job != null && job.usedBaritone) BaritoneBridge.cancel();
        });
        LlmCraftEvents.cancelled(reason);
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
        final CompletableFuture<String> result;
        /** Why the request failed (null while fine). */
        volatile String error;
        volatile boolean started;
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
        /** Failures per exact call (tool + arguments), with where the player was and what they held then. */
        final java.util.Map<String, int[]> failedCalls = new java.util.HashMap<>();
        String failedAt = "";
        String lastTool;
        int sameToolStreak;
        int failStreak;
        // ---- fresh context mode ----
        boolean fresh;
        String system;
        final List<Message> base = new ArrayList<>();
        final List<String> stepLog = new ArrayList<>();
        final List<String> lastResults = new ArrayList<>();
        final List<Message.Image> lastImages = new ArrayList<>();
        String lastText = "";
        String pinnedPlan;
        volatile String notes = "";
        final StringBuilder shownText = new StringBuilder();

        Job(String input, Source source, CompletableFuture<String> result) {
            this.input = input;
            this.source = source;
            this.result = result;
        }

        /** Completes the caller's future (once) and tells listeners how it went. */
        void settle() {
            String reply;
            synchronized (shownText) {
                reply = shownText.toString().trim();
            }
            String outcome = cancelled ? "cancelled" : error != null ? "error" : "done";
            boolean first = cancelled ? result.completeExceptionally(new java.util.concurrent.CancellationException("request cancelled"))
                    : error != null ? result.completeExceptionally(new IllegalStateException(error)) : result.complete(reply);
            if (first && started) LlmCraftEvents.requestFinished(input, source, reply, outcome);
        }

        /** Client thread: snapshot everything the request needs. */
        void begin() {
            try {
                cfg = ConfigManager.get();
                world = Stores.current();
                if (!world.settings().enabled) {
                    if (source == Source.USER) Output.error("The AI is disabled for this world/server (/llm world enable).");
                    error = "the AI is disabled for this world/server";
                    finished(this);
                    return;
                }
                String profileName = world.settings().profile;
                profile = profileName != null && !profileName.isBlank() ? cfg.profile(profileName) : cfg.activeProfile();
                if (profile == null) {
                    Output.error("No provider profile configured. Edit config/llmcraft/config.json.");
                    error = "no provider profile configured";
                    finished(this);
                    return;
                }
                String keyProblem = keyProblem(profile);
                if (keyProblem != null) {
                    Output.error(keyProblem);
                    error = keyProblem;
                    finished(this);
                    return;
                }
                provider = Providers.create(profile);
                toolCtx = new ToolContext(cfg, world, profile);

                system = PromptBuilder.systemPrompt(cfg, world);
                fresh = !"append".equalsIgnoreCase(cfg.agent.contextMode);
                if (profile.toolMode != ProviderProfile.ToolMode.NONE) {
                    for (Tool t : ToolRegistry.all()) {
                        if (Permissions.offered(t, world) && t.available.test(toolCtx)) request.tools.add(t.spec());
                    }
                }
                toolChars = toolChars(request.tools);
                base.add(Message.system(system));
                base.addAll(PromptBuilder.history(world, profile.contextTokens, system.length(), toolChars, profile.maxTokens));
                request.messages.addAll(base);
                // The volatile part (state, chat) goes last so the cached prefix stays valid.
                request.messages.add(Message.user(fresh ? workMessage(null) : input + "\n\n" + PromptBuilder.stateBlock(cfg, world)));
                ACTIVE = this;
                started = true;
                LlmCraftEvents.requestStarted(input, source);
                int needed = (system.length() + toolChars + input.length()) / PromptBuilder.CHARS_PER_TOKEN;
                if (needed > profile.contextTokens * 0.9 && !warnedContext) {
                    warnedContext = true;
                    Output.error("Prompt (~" + needed + " tokens incl. " + request.tools.size() + " tools) is close to the context window of '"
                            + profile.name + "' (" + profile.contextTokens + "). Raise contextTokens or turn off unused permission categories.");
                }
                if (source != Source.TRIGGER) Output.info("thinking… (" + profile.name + ")");
                loop().orTimeout(cfg.agent.requestTimeoutSeconds, TimeUnit.SECONDS)
                        .whenComplete((v, t) -> {
                            if (t != null && !cancelled) {
                                Throwable c = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
                                LlmCraft.LOGGER.warn("Request failed", c);
                                String msg = c instanceof java.util.concurrent.TimeoutException ? "request timed out" : c.getMessage();
                                error = msg == null ? c.getClass().getSimpleName() : msg;
                                Output.error("Error: " + error);
                            }
                            finished(this);
                        });
            } catch (Throwable t) {
                LlmCraft.LOGGER.error("Could not start request", t);
                Output.error("Could not start: " + t.getMessage());
                error = "could not start: " + t.getMessage();
                finished(this);
            }
        }

        CompletableFuture<Void> loop() {
            if (cancelled) return CompletableFuture.completedFuture(null);
            step++;
            CompletableFuture<Void> prepared;
            if (fresh && step > 1) prepared = ClientThread.call(() -> workMessage(null)).thenAccept(this::setWork);
            else {
                if (!fresh) fitToContext();
                prepared = CompletableFuture.completedFuture(null);
            }
            return prepared.thenCompose(x -> provider.chat(request)).thenCompose(resp -> {
                if (resp.promptTokens >= 0) lastPromptTokens = resp.promptTokens;
                if (cancelled) return CompletableFuture.completedFuture(null);
                showResponse(resp);
                if (fresh) lastText = resp.text == null ? "" : resp.text.trim();
                else request.messages.add(resp.toAssistantMessage());
                if (!resp.hasToolCalls()) {
                    complete(resp.text);
                    return CompletableFuture.completedFuture(null);
                }
                if (step >= cfg.agent.maxSteps) return wrapUp(resp);
                List<ToolCall> calls = resp.toolCalls;
                if (calls.size() > cfg.agent.maxToolCallsPerStep) calls = calls.subList(0, cfg.agent.maxToolCallsPerStep);
                lastResults.clear();
                lastImages.clear();
                CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                for (ToolCall call : calls) {
                    chain = chain.thenCompose(v -> cancelled ? CompletableFuture.completedFuture(null) : execute(call).thenAccept(result -> {
                        String text = result.text;
                        int max = cfg.agent.maxToolResultChars;
                        if (text.length() > max) text = text.substring(0, max) + "\n…(truncated, " + result.text.length() + " chars)";
                        if (fresh) {
                            String what = callSummary(call);
                            lastResults.add("> " + what + "\n" + text);
                            if (profile.vision) lastImages.addAll(result.images);
                            stepLog.add("step " + step + ": " + what + " -> " + abbreviate(oneLine(text), 240));
                            if (call.name().equals("plan") && !result.error) pinnedPlan = text;
                        } else {
                            Message m = Message.toolResult(call, text);
                            if (profile.vision) m.images.addAll(result.images);
                            request.messages.add(m);
                        }
                    }));
                }
                // Tool calls the model sent beyond the cap still need an answer for strict providers.
                for (ToolCall skipped : resp.toolCalls.subList(calls.size(), resp.toolCalls.size())) {
                    chain = chain.thenRun(() -> {
                        if (fresh) stepLog.add("step " + step + ": " + callSummary(skipped) + " -> not run (too many calls in one step)");
                        else request.messages.add(Message.toolResult(skipped, "ERROR: skipped, too many tool calls in one step"));
                    });
                }
                return chain.thenCompose(v -> loop());
            });
        }

        /** Step limit reached: answer the pending tool calls, then let the model report back once without acting. */
        CompletableFuture<Void> wrapUp(ChatResponse last) {
            Output.info("Step limit reached (agent.maxSteps = " + cfg.agent.maxSteps + "), asking for a summary.");
            String stop = "[system] You reached the step limit for this request. Do not call any tools now. "
                    + "In 1-3 short lines tell the player what you did, what is left, and that they can say 'continue'.";
            CompletableFuture<Void> prepared;
            if (fresh) {
                for (ToolCall c : last.toolCalls) stepLog.add("step " + step + ": " + callSummary(c) + " -> not run (step limit)");
                prepared = ClientThread.call(() -> workMessage(stop)).thenAccept(this::setWork);
            } else {
                for (ToolCall c : last.toolCalls) request.messages.add(Message.toolResult(c, "ERROR: not executed - step limit reached"));
                request.messages.add(Message.user(stop));
                prepared = CompletableFuture.completedFuture(null);
            }
            return prepared.thenCompose(x -> provider.chat(request)).handle((resp, t) -> {
                if (cancelled) return null;
                if (resp != null) {
                    ChatResponse textOnly = new ChatResponse();
                    textOnly.text = resp.text;
                    textOnly.promptTokens = resp.promptTokens;
                    textOnly.completionTokens = resp.completionTokens;
                    showResponse(textOnly);
                }
                complete(resp == null ? "" : resp.text);
                return null;
            });
        }

        CompletableFuture<ToolResult> execute(ToolCall call) {
            Tool tool = ToolRegistry.get(call.name());
            JsonObject args = call.arguments() == null ? new JsonObject() : call.arguments();
            if (tool == null || !Permissions.offered(tool, world)) {
                return CompletableFuture.completedFuture(ToolResult.error("unknown or unavailable tool '" + call.name() + "'"));
            }
            String sig = call.name() + args;
            return ClientThread.call(() -> {
                // The same failed call twice in a row: stop and re-think, unless something changed since.
                String here = situation();
                if (!here.equals(failedAt)) failedCalls.clear();
                int[] f = failedCalls.get(sig);
                if (f != null && f[0] >= 2) {
                    return new Gate("this exact call already failed twice here. Do not retry it: re-evaluate - look at the last errors, "
                            + "try a different method or target (unstuck if you are stuck), or tell the player what blocks you", false, tool.category, tool.summarize(args));
                }
                return gate(tool, args);
            }).thenCompose(gate -> {
                if (gate.error != null) {
                    actionLog.add(tool.name + " (refused)");
                    LlmCraftEvents.toolCalled(tool.name, gate.summary, "refused: " + gate.error, true);
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
                        LlmCraftEvents.toolCalled(tool.name, gate.summary, "denied by the player", true);
                        return CompletableFuture.completedFuture(ToolResult.error("the player denied this action"));
                    }
                    if (cfg.chat.showToolCalls) Output.tool(gate.summary);
                    if (tool.category == Category.BARITONE || gate.category == Category.BARITONE) usedBaritone = true;
                    boolean acting = ACTING.contains(gate.category);
                    return (acting ? ClientThread.call(Inv::counts) : CompletableFuture.completedFuture((java.util.Map<String, Integer>) null)).thenCompose(before -> {
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
                        LlmCraftEvents.toolCalled(tool.name, gate.summary, r.text, r.error);
                        return r;
                    }).thenCompose(r -> ClientThread.call(() -> {
                        // What changed and where things stand, so the model does not have to guess or look.
                        StringBuilder extra = new StringBuilder();
                        if (before != null && Minecraft.getInstance().player != null) {
                            String delta = Inv.delta(before, Inv.counts());
                            if (!delta.isEmpty()) extra.append("\ninventory: ").append(delta);
                            extra.append("\n[").append(Snapshot.statusLine(Minecraft.getInstance())).append(']');
                        }
                        String hazard = Safety.take();
                        if (hazard != null) extra.append('\n').append(hazard);
                        if (r.error && !r.text.contains("already failed twice")) {
                            failedAt = situation();
                            failedCalls.computeIfAbsent(sig, k -> new int[1])[0]++;
                        } else if (!r.error) failedCalls.remove(sig);
                        String stuck = r.error && MOVING.contains(tool.name) ? dev.silverx.llmcraft.game.Stuck.diagnose(Minecraft.getInstance()) : null;
                        if (stuck != null) extra.append("\n[stuck] ").append(stuck).append(" - call unstuck (it tries the quick ways out), then continue.");
                        String nudge = nudge(tool.name, r.error);
                        if (nudge != null) extra.append("\n[note] ").append(nudge);
                        return r.append(extra.toString());
                    }));
                    });
                });
            });
        }

        /** Rough "where am I and what do I have" (block position + item total): failure memory resets when it changes. */
        String situation() {
            var p = Minecraft.getInstance().player;
            if (p == null) return "";
            return p.blockPosition().getX() / 2 + "," + p.blockPosition().getY() / 2 + "," + p.blockPosition().getZ() / 2 + "|" + Inv.totalCount();
        }

        /** Replaces the last message (the only one that changes between steps) with a new work message. */
        void setWork(String text) {
            request.messages.clear();
            request.messages.addAll(base);
            Message m = Message.user(text);
            m.images.addAll(lastImages);
            request.messages.add(m);
        }

        /**
         * fresh mode: everything the next step needs in one message - nothing older is re-sent, so the
         * context does not grow, while the system prompt + tools + history before it stay cached.
         * Client thread (reads the game state).
         */
        String workMessage(String extra) {
            StringBuilder sb = new StringBuilder();
            sb.append(source == Source.USER ? "Request from your player: " : source == Source.SCRIPT ? "[request from your player's script/mod] " : "[automatic trigger] ")
                    .append(input).append("\n\n");
            if (!notes.isBlank()) sb.append("## Your notes (task_notes)\n").append(notes.trim()).append("\n\n");
            if (pinnedPlan != null) sb.append("## Your plan (from the plan tool)\n").append(pinnedPlan.trim()).append("\n\n");
            if (!stepLog.isEmpty()) {
                sb.append("## Already done in this request (do not repeat what succeeded)\n");
                int from = Math.max(0, stepLog.size() - Math.max(5, cfg.agent.stepLogEntries));
                if (from > 0) sb.append("(").append(from).append(" earlier actions not shown)\n");
                for (int i = from; i < stepLog.size(); i++) sb.append(stepLog.get(i)).append('\n');
                sb.append('\n');
            }
            if (!lastText.isEmpty()) sb.append("## What you said last step\n").append(abbreviate(lastText, 600)).append("\n\n");
            if (!lastResults.isEmpty()) {
                sb.append("## Results of your last step\n");
                for (String r : lastResults) sb.append(r).append("\n\n");
            }
            sb.append(PromptBuilder.stateBlock(cfg, world));
            sb.append('\n').append(extra != null ? extra : stepLog.isEmpty()
                    ? "Act on the request with tools, or just answer if it is only a question."
                    : "Continue with the next tools, or reply to the player (no tool call) when the request is done or you are blocked.");
            return sb.toString();
        }

        String callSummary(ToolCall call) {
            Tool t = ToolRegistry.get(call.name());
            JsonObject args = call.arguments() == null ? new JsonObject() : call.arguments();
            return t == null ? call.name() + " " + abbreviate(args.toString(), 160) : t.summarize(args);
        }

        /**
         * Small models get stuck probing block by block or retrying a failing method. Repetition is easy to
         * detect here and a note right inside the result works better than a rule in the system prompt.
         */
        String nudge(String toolName, boolean error) {
            sameToolStreak = toolName.equals(lastTool) ? sameToolStreak + 1 : 1;
            lastTool = toolName;
            failStreak = error ? failStreak + 1 : 0;
            boolean probing = PROBES.contains(toolName);
            if (probing && sameToolStreak >= 4 && sameToolStreak % 4 == 0) {
                return toolName + " " + sameToolStreak + " times in a row. Stop probing block by block: decide with what you know, or use one "
                        + "high-level call (mine finds and collects blocks itself, plan lists everything a goal needs).";
            }
            if (failStreak >= 3 && failStreak % 3 == 0) {
                return failStreak + " failed actions in a row. Change the method instead of retrying (e.g. mine a block instead of making it, "
                        + "move somewhere with room, get the missing tool/fuel first), or tell the player what blocks you.";
            }
            return null;
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
            boolean ask = mode == PermissionMode.ASK && !Approvals.sessionAllowed(cat) && !Boolean.TRUE.equals(tool.autoAllow.apply(toolCtx, args))
                    || Boolean.TRUE.equals(tool.alwaysAsk.apply(toolCtx, args));
            return new Gate(null, ask, cat, summary);
        }

        void showResponse(ChatResponse resp) {
            if (cfg.chat.showThinking && resp.thinking != null && !resp.thinking.isBlank()) Output.thinking(abbreviate(resp.thinking.trim(), 400));
            String text = resp.text == null ? "" : resp.text.trim();
            if (!text.isEmpty() && !isSilent(text)) {
                for (String line : text.split("\n+")) if (!line.isBlank()) Output.reply(plain(line.trim()));
                synchronized (shownText) {
                    if (!shownText.isEmpty()) shownText.append('\n');
                    shownText.append(text);
                }
                LlmCraftEvents.reply(text);
            }
            if (resp.promptTokens >= 0) LlmCraft.LOGGER.info("[llm] step {}: prompt {} tok, prefill {} ms, output {} tok", step, resp.promptTokens, resp.promptMillis, resp.completionTokens);
            if (cfg.chat.showTokenUsage && resp.promptTokens >= 0) Output.info("tokens: " + resp.promptTokens + " in / " + resp.completionTokens + " out"
                    + (resp.promptMillis >= 0 ? " | prefill " + resp.promptMillis + " ms" : ""));
        }

        void complete(String finalText) {
            if (cancelled) return;
            String userText = source == Source.USER ? input : source == Source.SCRIPT ? "[script] " + input : "[automatic trigger] " + input;
            String assistant = shownText.toString().trim();
            if (cfg.agent.keepToolSummariesInHistory && !actionLog.isEmpty()) {
                assistant = (assistant + "\n[actions: " + abbreviate(String.join("; ", actionLog), 700) + "]").trim();
            }
            if (!notes.isBlank()) assistant = (assistant + "\n[notes: " + abbreviate(oneLine(notes), 500) + "]").trim();
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

    /** Tools that move the player: a failure there gets a stuck diagnosis. */
    private static final java.util.Set<String> MOVING = java.util.Set.of("walk_to", "move", "baritone", "mine", "fight", "use_block", "unstuck");

    /** Read-only lookups that a stuck model tends to repeat endlessly. */
    private static final java.util.Set<String> PROBES = java.util.Set.of("get_block", "find_blocks", "look", "look_at", "screenshot", "player_status", "inventory",
            "nearby_entities", "world_info", "read_screen");

    /** Tool categories whose results get the inventory change and a status line. */
    private static final java.util.Set<Category> ACTING = java.util.EnumSet.of(Category.MOVEMENT, Category.INTERACTION, Category.INVENTORY,
            Category.BARITONE, Category.COMMANDS, Category.CLIENT_COMMANDS, Category.KEYBINDS, Category.SCREEN);

    /** Chat shows raw text: drop markdown emphasis, headings and code ticks that models add anyway. */
    static String plain(String line) {
        String s = line.replaceAll("^#{1,6}\\s+", "").replace("**", "").replace("__", "").replace("`", "");
        return s.replaceAll("^[*-]\\s+", "• ");
    }

    /** Explains a missing API key before a request is sent (instead of a cryptic 400/401 from the provider). */
    static String keyProblem(ProviderProfile p) {
        if (p.type == ProviderProfile.Type.OLLAMA || !p.resolvedApiKey().isBlank()) return null;
        String host = p.trimmedBaseUrl().replaceFirst("^https?://", "").replaceFirst("[:/].*$", "");
        if (host.equals("localhost") || host.startsWith("127.") || host.equals("0.0.0.0") || host.startsWith("192.168.") || host.startsWith("10.")) return null;
        String key = p.apiKey == null ? "" : p.apiKey.trim();
        if (key.startsWith("env:")) {
            return "No API key for profile '" + p.name + "': the environment variable " + key.substring(4).trim()
                    + " is not set for Minecraft. Set it in your launcher (Prism: Edit Instance -> Settings -> Environment variables) and restart the game,"
                    + " or save the key directly with /llm key " + p.name + " <key>.";
        }
        return "No API key for profile '" + p.name + "'. Save one with /llm key " + p.name + " <key> (or set \"apiKey\": \"env:VARIABLE\" in config.json).";
    }

    /** Who started the running request (attacking players needs the player's own order). */
    public static Source currentSource() {
        Job j = ACTIVE;
        return j == null || j != current ? null : j.source;
    }

    /** The running request (for tools that talk to it, like task_notes). */
    private static volatile Job ACTIVE;

    /** task_notes: the model's own scratchpad for the running request; shown in every following step. */
    public static String setNotes(String text) {
        Job j = ACTIVE;
        if (j == null || j != current) return "no request is running";
        j.notes = text == null ? "" : abbreviate(text.trim(), 1500);
        return null;
    }

    static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
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
