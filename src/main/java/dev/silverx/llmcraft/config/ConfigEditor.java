package dev.silverx.llmcraft.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Get/set any config value by a dotted path, e.g. "chat.minSendIntervalMs", "profiles.ollama.model",
 * "multiplayerPermissions.CHAT_SEND". List elements are addressed by their "name" field or index.
 */
public final class ConfigEditor {
    private ConfigEditor() {}

    public static String get(String path) {
        JsonElement root = ConfigManager.GSON.toJsonTree(ConfigManager.get());
        JsonElement el = path == null || path.isBlank() ? root : navigate(root, path.split("\\."), false);
        if (el == null) throw new IllegalArgumentException("no such setting: " + path);
        String s = ConfigManager.GSON.toJson(el);
        return redact(path, s);
    }

    public static void set(String path, String rawValue) {
        String[] parts = path.split("\\.");
        JsonObject root = ConfigManager.GSON.toJsonTree(ConfigManager.get()).getAsJsonObject();
        JsonElement parent = parts.length == 1 ? root : navigate(root, java.util.Arrays.copyOf(parts, parts.length - 1), false);
        if (parent == null) throw new IllegalArgumentException("no such setting: " + path);
        String last = parts[parts.length - 1];
        JsonElement value = parse(rawValue);
        if (parent.isJsonObject()) {
            JsonObject o = parent.getAsJsonObject();
            if (!o.has(last) && !isMap(parts)) throw new IllegalArgumentException("unknown key '" + last + "'");
            o.add(last, value);
        } else if (parent.isJsonArray()) {
            JsonArray arr = parent.getAsJsonArray();
            int i = Integer.parseInt(last);
            if (i == arr.size()) arr.add(value);
            else arr.set(i, value);
        } else {
            throw new IllegalArgumentException("cannot set inside a value");
        }
        LlmConfig updated = ConfigManager.GSON.fromJson(root, LlmConfig.class);
        updated.applyDefaults();
        ConfigManager.replace(updated);
    }

    private static boolean isMap(String[] parts) {
        String first = parts[0];
        return first.equals("modChatPrefixes") || first.endsWith("Permissions") || (parts.length >= 3 && parts[parts.length - 2].equals("headers"))
                || (parts.length >= 3 && parts[parts.length - 2].equals("extraBody"));
    }

    private static JsonElement navigate(JsonElement root, String[] parts, boolean create) {
        JsonElement cur = root;
        for (String p : parts) {
            if (cur == null) return null;
            if (cur.isJsonObject()) {
                cur = cur.getAsJsonObject().get(p);
            } else if (cur.isJsonArray()) {
                JsonElement found = null;
                for (JsonElement e : cur.getAsJsonArray()) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("name") && e.getAsJsonObject().get("name").getAsString().equalsIgnoreCase(p)) {
                        found = e;
                        break;
                    }
                }
                if (found == null) {
                    try {
                        found = cur.getAsJsonArray().get(Integer.parseInt(p));
                    } catch (Exception ignored) {
                    }
                }
                cur = found;
            } else {
                return null;
            }
        }
        return cur;
    }

    private static JsonElement parse(String raw) {
        String t = raw.trim();
        try {
            JsonElement e = JsonParser.parseString(t);
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && !t.startsWith("\"")) return new JsonPrimitive(t);
            return e;
        } catch (Exception e) {
            return new JsonPrimitive(t);
        }
    }

    private static String redact(String path, String json) {
        // Literal API keys are not echoed into chat (screenshots/streams).
        return json.replaceAll("(\"apiKey\"\\s*:\\s*\")(?!env:)([^\"]{4})[^\"]*\"", "$1$2…\"");
    }
}
