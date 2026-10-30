package dev.silverx.llmcraft.llm;

import com.google.gson.JsonObject;

/** A tool invocation requested by the model. */
public record ToolCall(String id, String name, JsonObject arguments) {}
