package dev.silverx.llmcraft.llm;

import com.google.gson.JsonObject;

/** A tool definition advertised to the model. {@code parameters} is a JSON schema object. */
public record ToolSpec(String name, String description, JsonObject parameters) {}
