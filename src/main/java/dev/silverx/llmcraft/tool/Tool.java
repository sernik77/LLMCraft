package dev.silverx.llmcraft.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.game.ClientThread;
import dev.silverx.llmcraft.llm.ToolSpec;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/** One capability offered to the model. */
public final class Tool {
    @FunctionalInterface
    public interface SyncHandler {
        ToolResult run(ToolContext ctx, JsonObject args) throws Exception;
    }

    public final String name;
    public final String description;
    public final Category category;
    public final JsonObject schema;
    /** Async handler; called on the client thread. */
    public final BiFunction<ToolContext, JsonObject, CompletableFuture<ToolResult>> handler;
    /** Whether this tool makes sense right now (e.g. Baritone installed, model has vision). */
    public final Predicate<ToolContext> available;
    /** Optional dynamic category (e.g. a client-side command is CLIENT_COMMANDS, a server one COMMANDS). */
    public final BiFunction<ToolContext, JsonObject, Category> categoryFor;
    /** Human readable one-liner for approvals and the activity log. */
    public final Function<JsonObject, String> summary;
    /** Returns a reason when this specific call must be refused regardless of permissions. */
    public final BiFunction<ToolContext, JsonObject, String> deny;
    /** Calls for which an ASK permission is skipped (e.g. whitelisted commands). */
    public final BiFunction<ToolContext, JsonObject, Boolean> autoAllow;
    /** Further categories under which this tool is offered (when its dynamic category can differ). */
    public final java.util.List<Category> alsoOfferedUnder;

    private Tool(Builder b) {
        this.name = b.name;
        this.description = b.description;
        this.category = b.category;
        this.schema = b.schema;
        this.handler = b.handler;
        this.available = b.available;
        this.categoryFor = b.categoryFor;
        this.summary = b.summary;
        this.deny = b.deny;
        this.autoAllow = b.autoAllow;
        this.alsoOfferedUnder = java.util.List.copyOf(b.alsoOfferedUnder);
    }

    public ToolSpec spec() {
        return new ToolSpec(name, description, schema);
    }

    public Category categoryFor(ToolContext ctx, JsonObject args) {
        return categoryFor == null ? category : categoryFor.apply(ctx, args);
    }

    public String summarize(JsonObject args) {
        if (summary != null) {
            try {
                return summary.apply(args);
            } catch (Exception ignored) {
            }
        }
        String a = args == null ? "{}" : args.toString();
        return name + (a.equals("{}") ? "" : " " + (a.length() > 160 ? a.substring(0, 160) + "…" : a));
    }

    public static Builder builder(String name, Category category, String description) {
        return new Builder(name, category, description);
    }

    public static final class Builder {
        private final String name;
        private final Category category;
        private final String description;
        private final JsonObject schema = new JsonObject();
        private final JsonObject props = new JsonObject();
        private final JsonArray required = new JsonArray();
        private BiFunction<ToolContext, JsonObject, CompletableFuture<ToolResult>> handler;
        private Predicate<ToolContext> available = ctx -> true;
        private BiFunction<ToolContext, JsonObject, Category> categoryFor;
        private Function<JsonObject, String> summary;
        private BiFunction<ToolContext, JsonObject, String> deny = (c, a) -> null;
        private BiFunction<ToolContext, JsonObject, Boolean> autoAllow = (c, a) -> false;
        private final java.util.List<Category> alsoOfferedUnder = new java.util.ArrayList<>();

        private Builder(String name, Category category, String description) {
            this.name = name;
            this.category = category;
            this.description = description;
            schema.addProperty("type", "object");
            schema.add("properties", props);
        }

        public Builder param(String name, String type, String description, boolean isRequired) {
            JsonObject p = new JsonObject();
            p.addProperty("type", type);
            p.addProperty("description", description);
            props.add(name, p);
            if (isRequired) required.add(name);
            return this;
        }

        /** A parameter with a hand-written JSON schema (nested arrays/objects). */
        public Builder rawParam(String name, JsonObject paramSchema, boolean isRequired) {
            props.add(name, paramSchema);
            if (isRequired) required.add(name);
            return this;
        }

        public Builder enumParam(String name, String description, boolean isRequired, String... values) {
            JsonObject p = new JsonObject();
            p.addProperty("type", "string");
            p.addProperty("description", description);
            JsonArray e = new JsonArray();
            for (String v : values) e.add(v);
            p.add("enum", e);
            props.add(name, p);
            if (isRequired) required.add(name);
            return this;
        }

        public Builder arrayParam(String name, String itemType, String description, boolean isRequired) {
            JsonObject p = new JsonObject();
            p.addProperty("type", "array");
            JsonObject items = new JsonObject();
            items.addProperty("type", itemType);
            p.add("items", items);
            p.addProperty("description", description);
            props.add(name, p);
            if (isRequired) required.add(name);
            return this;
        }

        /** Runs synchronously on the client thread. */
        public Builder sync(SyncHandler h) {
            this.handler = (ctx, args) -> ClientThread.call(() -> h.run(ctx, args));
            return this;
        }

        /** Starts on the client thread and may complete later (multi-tick actions). */
        public Builder async(BiFunction<ToolContext, JsonObject, CompletableFuture<ToolResult>> h) {
            this.handler = (ctx, args) -> ClientThread.call(() -> h.apply(ctx, args)).thenCompose(f -> f);
            return this;
        }

        public Builder available(Predicate<ToolContext> p) {
            this.available = p;
            return this;
        }

        public Builder categoryFor(BiFunction<ToolContext, JsonObject, Category> f) {
            this.categoryFor = f;
            return this;
        }

        public Builder summary(Function<JsonObject, String> f) {
            this.summary = f;
            return this;
        }

        public Builder alsoOfferedUnder(Category... categories) {
            alsoOfferedUnder.addAll(java.util.List.of(categories));
            return this;
        }

        public Builder deny(BiFunction<ToolContext, JsonObject, String> f) {
            this.deny = f;
            return this;
        }

        public Builder autoAllow(BiFunction<ToolContext, JsonObject, Boolean> f) {
            this.autoAllow = f;
            return this;
        }

        public Tool build() {
            if (!required.isEmpty()) schema.add("required", required);
            if (handler == null) throw new IllegalStateException("tool " + name + " has no handler");
            return new Tool(this);
        }
    }
}
