package dev.silverx.llmcraft.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Emulates tool calling for models without native support: tools are described in the system prompt and
 * the model answers with a JSON object {"tool_calls":[{"name":"...","arguments":{...}}]}.
 */
public final class JsonToolAdapter implements LlmProvider {
    private final LlmProvider inner;

    public JsonToolAdapter(LlmProvider inner) {
        this.inner = inner;
    }

    @Override
    public String describe() {
        return inner.describe() + " (json tools)";
    }

    @Override
    public CompletableFuture<ChatResponse> chat(ChatRequest request) {
        if (request.tools.isEmpty()) return inner.chat(request);
        ChatRequest converted = new ChatRequest();
        boolean systemSeen = false;
        for (Message m : request.messages) {
            switch (m.role) {
                case SYSTEM -> {
                    String text = m.textOrEmpty();
                    if (!systemSeen) text = text + "\n\n" + toolInstructions(request.tools);
                    systemSeen = true;
                    converted.messages.add(Message.system(text));
                }
                case ASSISTANT -> {
                    StringBuilder sb = new StringBuilder(m.textOrEmpty());
                    if (m.hasToolCalls()) {
                        JsonArray arr = new JsonArray();
                        for (ToolCall c : m.toolCalls) {
                            JsonObject o = new JsonObject();
                            o.addProperty("name", c.name());
                            o.add("arguments", c.arguments() == null ? new JsonObject() : c.arguments());
                            arr.add(o);
                        }
                        JsonObject wrapper = new JsonObject();
                        wrapper.add("tool_calls", arr);
                        if (!sb.isEmpty()) sb.append('\n');
                        sb.append(wrapper);
                    }
                    converted.messages.add(Message.assistant(sb.toString()));
                }
                case TOOL -> {
                    Message u = Message.user("[TOOL RESULT " + m.toolName + "]\n" + m.textOrEmpty());
                    u.images.addAll(m.images);
                    converted.messages.add(u);
                }
                default -> converted.messages.add(m);
            }
        }
        if (!systemSeen) converted.messages.addFirst(Message.system(toolInstructions(request.tools)));
        return inner.chat(converted).thenApply(JsonToolAdapter::extractCalls);
    }

    static String toolInstructions(List<ToolSpec> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("# TOOLS\nYou can call tools. To call one or more tools, reply with ONLY a JSON object, no other text:\n")
                .append("{\"tool_calls\":[{\"name\":\"tool_name\",\"arguments\":{...}}]}\n")
                .append("After the tools run you will receive [TOOL RESULT name] messages. ")
                .append("When you do not need a tool, reply with plain text (no JSON).\nAvailable tools:\n");
        for (ToolSpec t : tools) {
            sb.append("- ").append(t.name()).append(": ").append(t.description());
            JsonObject props = t.parameters() == null ? null : t.parameters().getAsJsonObject("properties");
            if (props != null && !props.isEmpty()) sb.append(" | params: ").append(props);
            sb.append('\n');
        }
        return sb.toString();
    }

    static ChatResponse extractCalls(ChatResponse r) {
        String text = r.text == null ? "" : r.text;
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = matchingBrace(text, start);
            if (end < 0) break;
            String candidate = text.substring(start, end + 1);
            List<ToolCall> calls = parseCalls(candidate);
            if (!calls.isEmpty()) {
                r.toolCalls.addAll(calls);
                String visible = (text.substring(0, start) + text.substring(end + 1))
                        .replaceAll("```(?:json)?\\s*```", "").trim();
                r.text = visible;
                return r;
            }
            start = text.indexOf('{', start + 1);
        }
        return r;
    }

    private static List<ToolCall> parseCalls(String json) {
        List<ToolCall> out = new ArrayList<>();
        JsonElement el;
        try {
            el = JsonParser.parseString(json);
        } catch (Exception e) {
            return out;
        }
        if (!el.isJsonObject()) return out;
        JsonObject o = el.getAsJsonObject();
        if (o.has("tool_calls") && o.get("tool_calls").isJsonArray()) {
            for (JsonElement c : o.getAsJsonArray("tool_calls")) {
                if (c.isJsonObject()) addCall(out, c.getAsJsonObject());
            }
        } else if (o.has("name") || o.has("tool")) {
            addCall(out, o);
        }
        return out;
    }

    private static void addCall(List<ToolCall> out, JsonObject c) {
        String name = HttpJson.str(c, "name");
        if (name == null) name = HttpJson.str(c, "tool");
        if (name == null || name.isBlank()) return;
        JsonElement args = c.has("arguments") ? c.get("arguments") : c.get("parameters");
        out.add(new ToolCall("call_" + UUID.randomUUID().toString().substring(0, 8), name, HttpJson.parseArguments(args)));
    }

    private static int matchingBrace(String s, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inString) {
                if (ch == '\\') i++;
                else if (ch == '"') inString = false;
            } else if (ch == '"') inString = true;
            else if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return i;
        }
        return -1;
    }
}
