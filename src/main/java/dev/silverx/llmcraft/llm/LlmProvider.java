package dev.silverx.llmcraft.llm;

import java.util.concurrent.CompletableFuture;

/** One backend endpoint. Implementations must never block the calling thread. */
public interface LlmProvider {
    CompletableFuture<ChatResponse> chat(ChatRequest request);

    /** Human readable label, e.g. "ollama qwen3.5:4b". */
    String describe();
}
