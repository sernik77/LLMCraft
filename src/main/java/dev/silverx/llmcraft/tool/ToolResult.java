package dev.silverx.llmcraft.tool;

import dev.silverx.llmcraft.llm.Message;

import java.util.ArrayList;
import java.util.List;

public final class ToolResult {
    public final String text;
    public final boolean error;
    public final List<Message.Image> images = new ArrayList<>();

    private ToolResult(String text, boolean error) {
        this.text = text;
        this.error = error;
    }

    public static ToolResult ok(String text) {
        return new ToolResult(text == null || text.isEmpty() ? "ok" : text, false);
    }

    public static ToolResult error(String text) {
        return new ToolResult("ERROR: " + text, true);
    }

    /** Same result with extra text appended (images are kept). */
    public ToolResult append(String extra) {
        if (extra == null || extra.isEmpty()) return this;
        ToolResult r = new ToolResult(text + extra, error);
        r.images.addAll(images);
        return r;
    }

    public ToolResult withImage(String mediaType, String base64) {
        images.add(new Message.Image(mediaType, base64));
        return this;
    }
}
