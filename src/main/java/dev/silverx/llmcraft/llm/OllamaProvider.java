package dev.silverx.llmcraft.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Native Ollama /api/chat (supports num_ctx, keep_alive, images and native tool calls). */
public final class OllamaProvider implements LlmProvider {
    private final ProviderProfile profile;

    public OllamaProvider(ProviderProfile profile) {
        this.profile = profile;
    }

    @Override
    public String describe() {
        return "ollama " + profile.model + " @ " + profile.trimmedBaseUrl();
    }

    @Override
    public CompletableFuture<ChatResponse> chat(ChatRequest request) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        String key = profile.resolvedApiKey();
        if (!key.isEmpty()) headers.put("Authorization", "Bearer " + key);
        profile.headers.forEach((k, v) -> headers.put(k, ProviderProfile.resolveSecret(v)));
        return HttpJson.post(profile.trimmedBaseUrl() + "/api/chat", headers, buildBody(request), profile.timeoutSeconds)
                .thenApply(OllamaProvider::parse);
    }

    JsonObject buildBody(ChatRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", profile.model);
        body.addProperty("stream", false);
        JsonObject options = new JsonObject();
        options.addProperty("temperature", profile.temperature);
        if (profile.contextTokens > 0) options.addProperty("num_ctx", profile.contextTokens);
        if (profile.maxTokens > 0) options.addProperty("num_predict", profile.maxTokens);
        body.add("options", options);

        JsonArray messages = new JsonArray();
        for (Message m : request.messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", switch (m.role) {
                case SYSTEM -> "system";
                case USER -> "user";
                case ASSISTANT -> "assistant";
                case TOOL -> "tool";
            });
            o.addProperty("content", m.textOrEmpty());
            if (m.role == Message.Role.TOOL && m.toolName != null) o.addProperty("tool_name", m.toolName);
            if (m.hasImages()) {
                JsonArray imgs = new JsonArray();
                for (Message.Image img : m.images) imgs.add(img.base64());
                o.add("images", imgs);
            }
            if (m.hasToolCalls()) {
                JsonArray calls = new JsonArray();
                for (ToolCall c : m.toolCalls) {
                    JsonObject fn = new JsonObject();
                    fn.addProperty("name", c.name());
                    fn.add("arguments", c.arguments() == null ? new JsonObject() : c.arguments());
                    JsonObject call = new JsonObject();
                    call.add("function", fn);
                    calls.add(call);
                }
                o.add("tool_calls", calls);
            }
            messages.add(o);
        }
        body.add("messages", messages);

        if (!request.tools.isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec t : request.tools) {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", t.name());
                fn.addProperty("description", t.description());
                fn.add("parameters", t.parameters());
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.add("function", fn);
                tools.add(tool);
            }
            body.add("tools", tools);
        }
        HttpJson.merge(body, profile.extraBody);
        return body;
    }

    static ChatResponse parse(JsonObject json) {
        if (json.has("error")) throw new LlmException("Ollama error: " + json.get("error"));
        ChatResponse r = new ChatResponse();
        JsonObject msg = json.getAsJsonObject("message");
        if (msg != null) {
            String content = HttpJson.str(msg, "content");
            r.text = content == null ? "" : content;
            String thinking = HttpJson.str(msg, "thinking");
            if (thinking != null) r.thinking = thinking;
            JsonArray calls = msg.getAsJsonArray("tool_calls");
            if (calls != null) {
                for (JsonElement el : calls) {
                    JsonObject fn = el.getAsJsonObject().getAsJsonObject("function");
                    if (fn == null) continue;
                    String id = HttpJson.str(el.getAsJsonObject(), "id");
                    if (id == null || id.isEmpty()) id = "call_" + UUID.randomUUID().toString().substring(0, 8);
                    r.toolCalls.add(new ToolCall(id, HttpJson.str(fn, "name"), HttpJson.parseArguments(fn.get("arguments"))));
                }
            }
        }
        r.finishReason = HttpJson.str(json, "done_reason");
        r.promptTokens = HttpJson.integer(json, "prompt_eval_count", -1);
        r.completionTokens = HttpJson.integer(json, "eval_count", -1);
        r.text = ThinkTags.strip(r);
        return r;
    }
}
