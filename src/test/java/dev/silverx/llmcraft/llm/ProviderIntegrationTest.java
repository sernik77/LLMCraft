package dev.silverx.llmcraft.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end check of every wire format against a local Ollama (which also speaks the OpenAI and
 * Anthropic formats). Skipped when Ollama is not reachable. Model: env LLMCRAFT_TEST_MODEL or qwen3.5:4b.
 */
class ProviderIntegrationTest {
    static final String BASE = System.getenv().getOrDefault("LLMCRAFT_TEST_OLLAMA", "http://localhost:11434");
    static final String MODEL = System.getenv().getOrDefault("LLMCRAFT_TEST_MODEL", "qwen3.5:4b");

    enum Format { OLLAMA_NATIVE, OPENAI_COMPAT, ANTHROPIC_COMPAT, JSON_MODE }

    @BeforeAll
    static void requireOllama() {
        boolean up;
        try {
            var resp = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(BASE + "/api/tags"))
                    .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
            up = resp.statusCode() == 200 && resp.body().contains(MODEL);
        } catch (Exception e) {
            up = false;
        }
        Assumptions.assumeTrue(up, "Ollama with " + MODEL + " not available at " + BASE);
    }

    static ProviderProfile profile(Format f) {
        ProviderProfile p = new ProviderProfile();
        p.model = MODEL;
        p.temperature = 0.0;
        p.timeoutSeconds = 300;
        p.contextTokens = 4096;
        switch (f) {
            case OLLAMA_NATIVE -> { p.type = ProviderProfile.Type.OLLAMA; p.baseUrl = BASE; p.extraBody.addProperty("think", false); }
            case OPENAI_COMPAT -> { p.type = ProviderProfile.Type.OPENAI; p.baseUrl = BASE + "/v1"; p.apiKey = "ollama"; }
            case ANTHROPIC_COMPAT -> { p.type = ProviderProfile.Type.ANTHROPIC; p.baseUrl = BASE; p.apiKey = "ollama"; p.maxTokens = 2048; }
            case JSON_MODE -> { p.type = ProviderProfile.Type.OLLAMA; p.baseUrl = BASE; p.toolMode = ProviderProfile.ToolMode.JSON; p.extraBody.addProperty("think", false); }
        }
        return p;
    }

    static List<ToolSpec> tools() {
        JsonObject add = JsonParser.parseString("""
                {"type":"object","properties":{"a":{"type":"integer","description":"first number"},
                 "b":{"type":"integer","description":"second number"}},"required":["a","b"]}""").getAsJsonObject();
        JsonObject weather = JsonParser.parseString("""
                {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}""").getAsJsonObject();
        return List.of(new ToolSpec("add_numbers", "Adds two integers and returns the exact sum.", add),
                new ToolSpec("get_weather", "Returns the current weather for a city.", weather));
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void toolRoundTrip(Format format) throws Exception {
        LlmProvider provider = Providers.create(profile(format));
        ChatRequest req = new ChatRequest(List.of(
                Message.system("You are a test assistant. Always use the provided tools for arithmetic."),
                Message.user("Use the add_numbers tool to compute 1234 + 4321, then tell me the result.")), tools());

        ChatResponse first = provider.chat(req).get(300, TimeUnit.SECONDS);
        assertTrue(first.hasToolCalls(), format + ": expected a tool call, got text: " + first.text);
        ToolCall call = first.toolCalls.getFirst();
        assertEquals("add_numbers", call.name(), format + ": wrong tool");
        int a = call.arguments().get("a").getAsInt();
        int b = call.arguments().get("b").getAsInt();
        assertEquals(5555, a + b, format + ": wrong args " + call.arguments());

        req.messages.add(first.toAssistantMessage());
        for (ToolCall c : first.toolCalls) {
            req.messages.add(Message.toolResult(c, c.name().equals("add_numbers") ? "{\"sum\":" + (a + b) + "}" : "{\"error\":\"unused\"}"));
        }
        ChatResponse second = provider.chat(req).get(300, TimeUnit.SECONDS);
        String text = second.text.replace(",", "").replace(" ", "");
        assertTrue(text.contains("5555"), format + ": final answer lacks the sum: " + second.text);
    }
}
