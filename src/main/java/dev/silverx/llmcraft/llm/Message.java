package dev.silverx.llmcraft.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider-neutral chat message. Images are PNG/JPEG payloads encoded as base64 (no data: prefix).
 */
public final class Message {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public Role role;
    public String text;
    public List<ToolCall> toolCalls = new ArrayList<>();
    /** For TOOL messages: id of the call this answers. */
    public String toolCallId;
    /** For TOOL messages: name of the tool that produced it. */
    public String toolName;
    public List<Image> images = new ArrayList<>();

    public record Image(String mediaType, String base64) {}

    public Message() {}

    public Message(Role role, String text) {
        this.role = role;
        this.text = text;
    }

    public static Message system(String text) { return new Message(Role.SYSTEM, text); }
    public static Message user(String text) { return new Message(Role.USER, text); }
    public static Message assistant(String text) { return new Message(Role.ASSISTANT, text); }

    public static Message assistant(String text, List<ToolCall> calls) {
        Message m = new Message(Role.ASSISTANT, text);
        if (calls != null) m.toolCalls.addAll(calls);
        return m;
    }

    public static Message toolResult(ToolCall call, String content) {
        Message m = new Message(Role.TOOL, content);
        m.toolCallId = call.id();
        m.toolName = call.name();
        return m;
    }

    public Message withImage(String mediaType, String base64) {
        images.add(new Image(mediaType, base64));
        return this;
    }

    public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }

    public boolean hasImages() { return images != null && !images.isEmpty(); }

    public String textOrEmpty() { return text == null ? "" : text; }
}
