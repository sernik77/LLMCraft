package dev.silverx.llmcraft.agent;

import dev.silverx.llmcraft.LlmCraft;
import dev.silverx.llmcraft.config.LlmConfig;
import dev.silverx.llmcraft.data.HistoryEntry;
import dev.silverx.llmcraft.data.WorldStore;
import dev.silverx.llmcraft.llm.ChatRequest;
import dev.silverx.llmcraft.llm.LlmProvider;
import dev.silverx.llmcraft.llm.Message;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps per-world history bounded by folding old turns into a running summary. */
final class History {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private History() {}

    static void maybeCompact(WorldStore world, LlmProvider provider, LlmConfig cfg) {
        List<HistoryEntry> all = world.history();
        int max = Math.max(4, cfg.agent.maxHistoryMessages);
        if (all.size() <= max) return;
        int fold = all.size() - max / 2;
        if (!cfg.agent.summarizeHistory) {
            world.compact(fold, null);
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) return;
        StringBuilder transcript = new StringBuilder();
        for (int i = 0; i < fold; i++) {
            HistoryEntry e = all.get(i);
            transcript.append(e.role).append(": ").append(e.text).append('\n');
        }
        String previous = world.summary();
        ChatRequest req = new ChatRequest();
        req.messages.add(Message.system("You maintain the long-term notes of a Minecraft AI companion. Merge the previous summary and the "
                + "conversation into one concise summary (max ~250 words, bullet points). Keep facts, coordinates, names, decisions, "
                + "open tasks and the player's preferences. Drop small talk. Output only the summary."));
        req.messages.add(Message.user("Previous summary:\n" + (previous.isBlank() ? "(none)" : previous) + "\n\nConversation to fold in:\n" + transcript));
        provider.chat(req).whenComplete((resp, t) -> {
            try {
                if (t != null || resp == null || resp.text.isBlank()) {
                    LlmCraft.LOGGER.warn("History summary failed, dropping old turns instead", t);
                    world.compact(fold, previous);
                } else {
                    world.compact(fold, resp.text);
                }
            } finally {
                RUNNING.set(false);
            }
        });
    }
}
