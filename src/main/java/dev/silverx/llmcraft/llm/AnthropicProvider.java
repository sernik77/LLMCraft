package dev.silverx.llmcraft.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Anthropic Messages API (Claude). */
public final class AnthropicProvider implements LlmProvider {
    private final ProviderProfile profile;

    public AnthropicProvider(ProviderProfile profile) {
        this.profile = profile;
    }

    @Override
    public String describe() {
        return "anthropic " + profile.model;
    }

    @Override
    public CompletableFuture<ChatResponse> chat(ChatRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", profile.resolvedApiKey());
        headers.put("anthropic-version", "2023-06-01");
        profile.headers.forEach((k, v) -> headers.put(k, ProviderProfile.resolveSecret(v)));
        String base = profile.trimmedBaseUrl();
        String url = base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
        return HttpJson.post(url, headers, buildBody(request), profile.timeoutSeconds).thenApply(AnthropicProvider::parse);
    }

    JsonObject buildBody(ChatRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", profile.model);
        body.addProperty("max_tokens", profile.maxTokens > 0 ? profile.maxTokens : 4096);
        body.addProperty("temperature", Math.min(1.0, profile.temperature));

        StringBuilder system = new StringBuilder();
        JsonArray messages = new JsonArray();
        String lastRole = null;
        JsonArray current = null;
        for (Message m : request.messages) {
            if (m.role == Message.Role.SYSTEM) {
                if (!system.isEmpty()) system.append("\n\n");
                system.append(m.textOrEmpty());
                continue;
            }
            String role = m.role == Message.Role.ASSISTANT ? "assistant" : "user";
            if (!role.equals(lastRole)) {
                current = new JsonArray();
                JsonObject o = new JsonObject();
                o.addProperty("role", role);
                o.add("content", current);
                messages.add(o);
                lastRole = role;
            }
            switch (m.role) {
                case USER -> {
                    for (Message.Image img : m.images) current.add(imageBlock(img));
                    if (!m.textOrEmpty().isEmpty() || !m.hasImages()) current.add(textBlock(m.textOrEmpty().isEmpty() ? "." : m.text));
                }
                case ASSISTANT -> {
                    if (!m.textOrEmpty().isEmpty()) current.add(textBlock(m.text));
                    for (ToolCall c : m.toolCalls) {
                        JsonObject b = new JsonObject();
                        b.addProperty("type", "tool_use");
                        b.addProperty("id", c.id());
                        b.addProperty("name", c.name());
                        b.add("input", c.arguments() == null ? new JsonObject() : c.arguments());
                        current.add(b);
                    }
                    if (current.isEmpty()) current.add(textBlock("."));
                }
                case TOOL -> {
                    JsonObject b = new JsonObject();
                    b.addProperty("type", "tool_result");
                    b.addProperty("tool_use_id", m.toolCallId);
                    JsonArray content = new JsonArray();
                    content.add(textBlock(m.textOrEmpty().isEmpty() ? "(empty)" : m.text));
                    for (Message.Image img : m.images) content.add(imageBlock(img));
                    b.add("content", content);
                    current.add(b);
                }
                default -> {}
            }
        }
        if (!system.isEmpty()) body.addProperty("system", system.toString());
        body.add("messages", messages);

        if (!request.tools.isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec t : request.tools) {
                JsonObject o = new JsonObject();
                o.addProperty("name", t.name());
                o.addProperty("description", t.description());
                o.add("input_schema", t.parameters());
                tools.add(o);
            }
            body.add("tools", tools);
        }
        HttpJson.merge(body, profile.extraBody);
        return body;
    }

    private static JsonObject textBlock(String text) {
        JsonObject b = new JsonObject();
        b.addProperty("type", "text");
        b.addProperty("text", text);
        return b;
    }

    private static JsonObject imageBlock(Message.Image img) {
        JsonObject src = new JsonObject();
        src.addProperty("type", "base64");
        src.addProperty("media_type", img.mediaType());
        src.addProperty("data", img.base64());
        JsonObject b = new JsonObject();
        b.addProperty("type", "image");
        b.add("source", src);
        return b;
    }

    static ChatResponse parse(JsonObject json) {
        if ("error".equals(HttpJson.str(json, "type"))) throw new LlmException("Anthropic error: " + json.get("error"));
        ChatResponse r = new ChatResponse();
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        JsonArray content = json.getAsJsonArray("content");
        if (content != null) {
            for (JsonElement el : content) {
                JsonObject b = el.getAsJsonObject();
                String type = HttpJson.str(b, "type");
                if ("text".equals(type)) text.append(HttpJson.str(b, "text"));
                else if ("thinking".equals(type)) thinking.append(HttpJson.str(b, "thinking"));
                else if ("tool_use".equals(type)) {
                    r.toolCalls.add(new ToolCall(HttpJson.str(b, "id"), HttpJson.str(b, "name"), HttpJson.parseArguments(b.get("input"))));
                }
            }
        }
        r.text = text.toString().trim();
        r.thinking = thinking.toString();
        r.finishReason = HttpJson.str(json, "stop_reason");
        JsonObject usage = json.getAsJsonObject("usage");
        if (usage != null) {
            r.promptTokens = HttpJson.integer(usage, "input_tokens", -1);
            r.completionTokens = HttpJson.integer(usage, "output_tokens", -1);
        }
        return r;
    }
}
