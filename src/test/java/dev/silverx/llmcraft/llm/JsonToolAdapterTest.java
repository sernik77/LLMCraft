package dev.silverx.llmcraft.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonToolAdapterTest {
    @Test
    void extractsFencedToolCalls() {
        ChatResponse r = new ChatResponse();
        r.text = "Sure!\n```json\n{\"tool_calls\":[{\"name\":\"send_chat\",\"arguments\":{\"message\":\"hi {there}\"}}]}\n```";
        JsonToolAdapter.extractCalls(r);
        assertEquals(1, r.toolCalls.size());
        assertEquals("send_chat", r.toolCalls.getFirst().name());
        assertEquals("hi {there}", r.toolCalls.getFirst().arguments().get("message").getAsString());
        assertEquals("Sure!", r.text);
    }

    @Test
    void acceptsSingleCallShape() {
        ChatResponse r = new ChatResponse();
        r.text = "{\"tool\":\"look\",\"parameters\":{\"yaw\":90}}";
        JsonToolAdapter.extractCalls(r);
        assertEquals("look", r.toolCalls.getFirst().name());
        assertEquals(90, r.toolCalls.getFirst().arguments().get("yaw").getAsInt());
    }

    @Test
    void leavesPlainTextAlone() {
        ChatResponse r = new ChatResponse();
        r.text = "No tools needed {really}.";
        JsonToolAdapter.extractCalls(r);
        assertTrue(r.toolCalls.isEmpty());
        assertEquals("No tools needed {really}.", r.text);
    }

    @Test
    void stripsThinkTags() {
        ChatResponse r = new ChatResponse();
        r.text = "<think>plan</think>Answer";
        assertEquals("Answer", ThinkTags.strip(r));
        assertEquals("plan", r.thinking);
    }
}
