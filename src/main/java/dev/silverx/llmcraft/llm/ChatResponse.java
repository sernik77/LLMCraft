package dev.silverx.llmcraft.llm;

import java.util.ArrayList;
import java.util.List;

public final class ChatResponse {
    public String text = "";
    public String thinking = "";
    public final List<ToolCall> toolCalls = new ArrayList<>();
    public String finishReason = "";
    public int promptTokens = -1;
    public int completionTokens = -1;
    /** Time the server spent on the prompt (prefill), ms; -1 if unknown. Small from step 2 on = the prefix cache works. */
    public long promptMillis = -1;

    public boolean hasToolCalls() { return !toolCalls.isEmpty(); }

    public Message toAssistantMessage() {
        return Message.assistant(text, toolCalls);
    }
}
