package dev.silverx.llmcraft.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI Chat Completions format. Works with OpenAI, OpenRouter, Groq, Together, DeepSeek, Mistral,
 * LM Studio, llama.cpp server, vLLM, Ollama's /v1 endpoint, Gemini's OpenAI-compatible endpoint, etc.
 */
public final class OpenAiProvider implements LlmProvider {
    private final ProviderProfile profile;

    public OpenAiProvider(ProviderProfile profile) {
        this.profile = profile;
    }

    @Override
    public String describe() {
        return "openai-compatible " + profile.model + " @ " + profile.trimmedBaseUrl();
    }

    @Override
    public CompletableFuture<ChatResponse> chat(ChatRequest request) {
        JsonObject body = buildBody(request);
        Map<String, String> headers = new LinkedHashMap<>();
        String key = profile.resolvedApiKey();
        if (!key.isEmpty()) headers.put("Authorization", "Bearer " + key);
        profile.headers.forEach((k, v) -> headers.put(k, ProviderProfile.resolveSecret(v)));
        String url = profile.trimmedBaseUrl() + "/chat/completions";
        return HttpJson.post(url, headers, body, profile.timeoutSeconds).thenApply(OpenAiProvider::parse);
    }

    JsonObject buildBody(ChatRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", profile.model);
        body.addProperty("temperature", profile.temperature);
        if (profile.maxTokens > 0) body.addProperty(profile.maxTokensField == null || profile.maxTokensField.isBlank() ? "max_tokens" : profile.maxTokensField, profile.maxTokens);

        JsonArray messages = new JsonArray();
        JsonArray pendingImages = new JsonArray();
        for (Message m : request.messages) {
            if (m.role != Message.Role.TOOL && !pendingImages.isEmpty()) {
                messages.add(imageUserMessage(pendingImages));
                pendingImages = new JsonArray();
            }
            JsonObject o = new JsonObject();
            switch (m.role) {
                case SYSTEM -> {
                    o.addProperty("role", "system");
                    o.addProperty("content", m.textOrEmpty());
                }
                case USER -> {
                    o.addProperty("role", "user");
                    if (m.hasImages()) {
                        JsonArray parts = new JsonArray();
                        JsonObject text = new JsonObject();
                        text.addProperty("type", "text");
                        text.addProperty("text", m.textOrEmpty());
                        parts.add(text);
                        for (Message.Image img : m.images) parts.add(imagePart(img));
                        o.add("content", parts);
                    } else {
                        o.addProperty("content", m.textOrEmpty());
                    }
                }
                case ASSISTANT -> {
                    o.addProperty("role", "assistant");
                    if (m.text == null || m.text.isEmpty()) {
                        o.add("content", m.hasToolCalls() ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(""));
                    } else {
                        o.addProperty("content", m.text);
                    }
                    if (m.hasToolCalls()) {
                        JsonArray calls = new JsonArray();
                        for (ToolCall c : m.toolCalls) {
                            JsonObject call = new JsonObject();
                            call.addProperty("id", c.id());
                            call.addProperty("type", "function");
                            JsonObject fn = new JsonObject();
                            fn.addProperty("name", c.name());
                            fn.addProperty("arguments", c.arguments() == null ? "{}" : c.arguments().toString());
                            call.add("function", fn);
                            calls.add(call);
                        }
                        o.add("tool_calls", calls);
                    }
                }
                case TOOL -> {
                    o.addProperty("role", "tool");
                    o.addProperty("tool_call_id", m.toolCallId);
                    o.addProperty("content", m.textOrEmpty());
                    // Tool messages can't carry images in this format; attach them in a user message right after.
                    for (Message.Image img : m.images) pendingImages.add(imagePart(img));
                }
            }
            messages.add(o);
        }
        if (!pendingImages.isEmpty()) messages.add(imageUserMessage(pendingImages));
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

    private static JsonObject imagePart(Message.Image img) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "image_url");
        JsonObject url = new JsonObject();
        url.addProperty("url", "data:" + img.mediaType() + ";base64," + img.base64());
        part.add("image_url", url);
        return part;
    }

    private static JsonObject imageUserMessage(JsonArray imageParts) {
        JsonArray parts = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", "(Image(s) returned by the previous tool call.)");
        parts.add(text);
        parts.addAll(imageParts);
        JsonObject o = new JsonObject();
        o.addProperty("role", "user");
        o.add("content", parts);
        return o;
    }

    static ChatResponse parse(JsonObject json) {
        ChatResponse r = new ChatResponse();
        if (json.has("error")) throw new LlmException("Provider error: " + json.get("error"));
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) throw new LlmException("No choices in response: " + HttpJson.abbreviate(json.toString(), 300));
        JsonObject choice = choices.get(0).getAsJsonObject();
        r.finishReason = HttpJson.str(choice, "finish_reason");
        JsonObject msg = choice.getAsJsonObject("message");
        if (msg != null) {
            JsonElement content = msg.get("content");
            if (content != null && !content.isJsonNull()) {
                if (content.isJsonArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonElement p : content.getAsJsonArray()) {
                        if (p.isJsonObject() && p.getAsJsonObject().has("text")) sb.append(p.getAsJsonObject().get("text").getAsString());
                    }
                    r.text = sb.toString();
                } else {
                    r.text = content.getAsString();
                }
            }
            String reasoning = HttpJson.str(msg, "reasoning_content");
            if (reasoning == null) reasoning = HttpJson.str(msg, "reasoning");
            if (reasoning != null) r.thinking = reasoning;
            JsonArray calls = msg.getAsJsonArray("tool_calls");
            if (calls != null) {
                for (JsonElement el : calls) {
                    JsonObject call = el.getAsJsonObject();
                    JsonObject fn = call.getAsJsonObject("function");
                    if (fn == null) continue;
                    String id = HttpJson.str(call, "id");
                    if (id == null || id.isEmpty()) id = "call_" + UUID.randomUUID().toString().substring(0, 8);
                    r.toolCalls.add(new ToolCall(id, HttpJson.str(fn, "name"), HttpJson.parseArguments(fn.get("arguments"))));
                }
            }
        }
        JsonObject usage = json.getAsJsonObject("usage");
        if (usage != null) {
            r.promptTokens = HttpJson.integer(usage, "prompt_tokens", -1);
            r.completionTokens = HttpJson.integer(usage, "completion_tokens", -1);
        }
        r.text = ThinkTags.strip(r);
        return r;
    }
}
