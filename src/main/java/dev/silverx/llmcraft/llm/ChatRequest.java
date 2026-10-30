package dev.silverx.llmcraft.llm;

import java.util.ArrayList;
import java.util.List;

public final class ChatRequest {
    public final List<Message> messages = new ArrayList<>();
    public final List<ToolSpec> tools = new ArrayList<>();

    public ChatRequest() {}

    public ChatRequest(List<Message> messages, List<ToolSpec> tools) {
        if (messages != null) this.messages.addAll(messages);
        if (tools != null) this.tools.addAll(tools);
    }
}
