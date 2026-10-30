package dev.silverx.llmcraft.tool;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Lenient argument readers: small models often send numbers as strings and vice versa. */
public final class Args {
    private Args() {}

    public static String str(JsonObject a, String key, String def) {
        JsonElement e = a == null ? null : a.get(key);
        if (e == null || e.isJsonNull()) return def;
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    public static String reqStr(JsonObject a, String key) {
        String s = str(a, key, null);
        if (s == null || s.isBlank()) throw new IllegalArgumentException("missing argument '" + key + "'");
        return s;
    }

    public static double num(JsonObject a, String key, double def) {
        JsonElement e = a == null ? null : a.get(key);
        if (e == null || e.isJsonNull()) return def;
        try {
            return e.getAsDouble();
        } catch (Exception ex) {
            try {
                return Double.parseDouble(e.getAsString().trim());
            } catch (Exception ex2) {
                return def;
            }
        }
    }

    public static double reqNum(JsonObject a, String key) {
        double v = num(a, key, Double.NaN);
        if (Double.isNaN(v)) throw new IllegalArgumentException("missing numeric argument '" + key + "'");
        return v;
    }

    public static int integer(JsonObject a, String key, int def) {
        double d = num(a, key, Double.NaN);
        return Double.isNaN(d) ? def : (int) Math.round(d);
    }

    public static boolean bool(JsonObject a, String key, boolean def) {
        JsonElement e = a == null ? null : a.get(key);
        if (e == null || e.isJsonNull()) return def;
        try {
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
            String s = e.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            return s.equals("true") || s.equals("yes") || s.equals("1");
        } catch (Exception ex) {
            return def;
        }
    }

    public static boolean has(JsonObject a, String key) {
        return a != null && a.has(key) && !a.get(key).isJsonNull();
    }
}
