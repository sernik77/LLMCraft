package dev.silverx.llmcraft.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/** Minimal async JSON-over-HTTP helper shared by the providers. */
public final class HttpJson {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "llmcraft-http");
                t.setDaemon(true);
                return t;
            }))
            .build();

    private HttpJson() {}

    public static CompletableFuture<JsonObject> post(String url, Map<String, String> headers, JsonObject body, int timeoutSeconds) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        headers.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) b.header(k, v);
        });
        return CLIENT.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    String text = resp.body() == null ? "" : resp.body();
                    if (resp.statusCode() / 100 != 2) {
                        throw new LlmException("HTTP " + resp.statusCode() + " from " + url + ": " + abbreviate(text, 600),
                                resp.statusCode(), null);
                    }
                    JsonElement el;
                    try {
                        el = JsonParser.parseString(text);
                    } catch (Exception e) {
                        throw new LlmException("Invalid JSON from " + url + ": " + abbreviate(text, 300), resp.statusCode(), e);
                    }
                    if (!el.isJsonObject()) throw new LlmException("Unexpected JSON from " + url + ": " + abbreviate(text, 300));
                    return el.getAsJsonObject();
                });
    }

    public static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Shallow merge, used for {@link ProviderProfile#extraBody}. Nested objects are merged recursively. */
    public static void merge(JsonObject target, JsonObject extra) {
        if (extra == null) return;
        for (var e : extra.entrySet()) {
            JsonElement existing = target.get(e.getKey());
            if (existing != null && existing.isJsonObject() && e.getValue().isJsonObject()) {
                merge(existing.getAsJsonObject(), e.getValue().getAsJsonObject());
            } else {
                target.add(e.getKey(), e.getValue().deepCopy());
            }
        }
    }

    public static String str(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : (e.isJsonPrimitive() ? e.getAsString() : e.toString());
    }

    public static int integer(JsonObject o, String key, int def) {
        if (o == null) return def;
        JsonElement e = o.get(key);
        try {
            return e == null || e.isJsonNull() ? def : e.getAsInt();
        } catch (Exception ex) {
            return def;
        }
    }

    /** Parse tool arguments that may arrive as a JSON string or an object. */
    public static JsonObject parseArguments(JsonElement el) {
        if (el == null || el.isJsonNull()) return new JsonObject();
        if (el.isJsonObject()) return el.getAsJsonObject();
        if (el.isJsonPrimitive()) {
            String s = el.getAsString().trim();
            if (s.isEmpty()) return new JsonObject();
            try {
                JsonElement parsed = JsonParser.parseString(s);
                if (parsed.isJsonObject()) return parsed.getAsJsonObject();
            } catch (Exception ignored) {
            }
            JsonObject wrapped = new JsonObject();
            wrapped.addProperty("_raw", s);
            return wrapped;
        }
        return new JsonObject();
    }
}
