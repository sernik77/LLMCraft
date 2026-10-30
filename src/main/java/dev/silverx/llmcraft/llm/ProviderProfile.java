package dev.silverx.llmcraft.llm;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** One configured AI backend. Serialized as part of the global config. */
public final class ProviderProfile {
    public enum Type { OPENAI, OLLAMA, ANTHROPIC }

    public enum ToolMode {
        /** Use the provider's native function/tool calling. */
        NATIVE,
        /** Describe tools in the prompt and parse a JSON block from the reply (for models without tool support). */
        JSON,
        /** Never offer tools: plain chatbot. */
        NONE
    }

    public String name = "ollama";
    public Type type = Type.OLLAMA;
    /** OpenAI: https://api.openai.com/v1, Ollama: http://localhost:11434, Anthropic: https://api.anthropic.com */
    public String baseUrl = "http://localhost:11434";
    /** Literal key, or "env:VARIABLE_NAME" to read from the environment. */
    public String apiKey = "";
    public String model = "qwen3.5:4b";
    public double temperature = 0.7;
    /** Max tokens to generate. 0 = provider default (Anthropic falls back to 4096). */
    public int maxTokens = 0;
    /** OpenAI-compatible only: name of the max token field ("max_tokens" or "max_completion_tokens"). */
    public String maxTokensField = "max_tokens";
    /** Approximate context window, used to budget history. Ollama: also sent as num_ctx. */
    public int contextTokens = 8192;
    public ToolMode toolMode = ToolMode.NATIVE;
    /** Whether the model accepts images (enables the screenshot tool). */
    public boolean vision = false;
    public int timeoutSeconds = 120;
    /** Extra HTTP headers, e.g. OpenRouter's HTTP-Referer. Values support "env:VAR". */
    public Map<String, String> headers = new LinkedHashMap<>();
    /** Merged into the request body last, e.g. {"keep_alive":"30m","think":false} or {"reasoning_effort":"low"}. */
    public JsonObject extraBody = new JsonObject();

    public ProviderProfile() {}

    public static ProviderProfile of(String name, Type type, String baseUrl, String apiKey, String model) {
        ProviderProfile p = new ProviderProfile();
        p.name = name;
        p.type = type;
        p.baseUrl = baseUrl;
        p.apiKey = apiKey;
        p.model = model;
        return p;
    }

    public String resolvedApiKey() {
        return resolveSecret(apiKey);
    }

    public static String resolveSecret(String value) {
        if (value == null) return "";
        if (value.startsWith("env:")) {
            String v = System.getenv(value.substring(4).trim());
            return v == null ? "" : v;
        }
        return value;
    }

    public String trimmedBaseUrl() {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
}
