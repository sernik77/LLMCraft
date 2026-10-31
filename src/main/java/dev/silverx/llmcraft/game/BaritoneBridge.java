package dev.silverx.llmcraft.game;

import java.lang.reflect.Method;

/**
 * Talks to Baritone through reflection so there is no compile-time dependency.
 * Works with standalone Baritone and Meteor's baritone fork (both ship baritone.api).
 */
public final class BaritoneBridge {
    private BaritoneBridge() {}

    public static boolean available() {
        try {
            Class.forName("baritone.api.BaritoneAPI");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object primary() throws ReflectiveOperationException {
        Class<?> api = Class.forName("baritone.api.BaritoneAPI");
        Object provider = api.getMethod("getProvider").invoke(null);
        return provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        Method m = findMethod(target.getClass(), method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
        // Prefer interface methods (implementation classes may be obfuscated/non-public).
        for (Class<?> i : allInterfaces(c)) {
            try {
                return i.getMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return c.getMethod(name, params);
    }

    private static java.util.List<Class<?>> allInterfaces(Class<?> c) {
        java.util.List<Class<?>> out = new java.util.ArrayList<>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) {
                out.add(i);
                out.addAll(allInterfaces(i));
            }
        }
        return out;
    }

    /** Executes a Baritone command such as "goto 100 70 100", "mine diamond_ore", "follow player Alex". */
    public static boolean execute(String command) throws ReflectiveOperationException {
        Object baritone = primary();
        Object manager = call(baritone, "getCommandManager");
        Method exec = findMethod(manager.getClass(), "execute", String.class);
        Object r = exec.invoke(manager, command);
        return !(r instanceof Boolean b) || b;
    }

    public static boolean isPathing() {
        try {
            Object behavior = call(primary(), "getPathingBehavior");
            return (Boolean) call(behavior, "isPathing");
        } catch (Throwable t) {
            return false;
        }
    }

    /** True while any Baritone process (mine, follow, goto...) is active. */
    public static boolean isBusy() {
        try {
            Object control = call(primary(), "getPathingControlManager");
            Object current = call(control, "mostRecentInControl");
            if (current instanceof java.util.Optional<?> opt) return opt.isPresent() || isPathing();
            return isPathing();
        } catch (Throwable t) {
            return isPathing();
        }
    }

    /** True while Baritone is computing a path (no movement yet, but it is working). */
    public static boolean isCalculating() {
        try {
            Object behavior = call(primary(), "getPathingBehavior");
            Object inProgress = call(behavior, "getInProgress");
            return inProgress instanceof java.util.Optional<?> o && o.isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    /** "name1/alias - short description" for every command of the installed Baritone (its own help texts). */
    public static java.util.List<String> commands() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            Object manager = call(primary(), "getCommandManager");
            Object registry = call(manager, "getRegistry");
            java.util.Collection<?> entries = (java.util.Collection<?>) registry.getClass().getField("entries").get(registry);
            for (Object cmd : entries) {
                if ((Boolean) call(cmd, "hiddenFromHelp")) continue;
                @SuppressWarnings("unchecked")
                java.util.List<String> names = (java.util.List<String>) call(cmd, "getNames");
                out.add(String.join("/", names) + " - " + call(cmd, "getShortDesc"));
            }
        } catch (Throwable ignored) {
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Long help (usage lines) of one command, or null if there is no such command. */
    public static String commandHelp(String name) {
        try {
            Object manager = call(primary(), "getCommandManager");
            Method get = findMethod(manager.getClass(), "getCommand", String.class);
            Object cmd = get.invoke(manager, name.toLowerCase(java.util.Locale.ROOT));
            if (cmd == null) return null;
            @SuppressWarnings("unchecked")
            java.util.List<String> lines = (java.util.List<String>) call(cmd, "getLongDesc");
            return call(cmd, "getShortDesc") + "\n" + String.join("\n", lines);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object settings() throws ReflectiveOperationException {
        return Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
    }

    /** "name = value (default x)" for the setting, or null when unknown. */
    public static String setting(String name) {
        try {
            Object settings = settings();
            java.util.Map<?, ?> byName = (java.util.Map<?, ?>) settings.getClass().getField("byLowerName").get(settings);
            Object s = byName.get(name.toLowerCase(java.util.Locale.ROOT));
            if (s == null) return null;
            Object value = s.getClass().getField("value").get(s);
            Object def = s.getClass().getField("defaultValue").get(s);
            return call(s, "getName") + " = " + value + (java.util.Objects.equals(value, def) ? "" : " (default " + def + ")");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Setting names containing the text (for finding the right one). */
    public static java.util.List<String> settingNames(String filter) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            Object settings = settings();
            java.util.Map<?, ?> byName = (java.util.Map<?, ?>) settings.getClass().getField("byLowerName").get(settings);
            String f = filter == null ? "" : filter.toLowerCase(java.util.Locale.ROOT);
            for (Object k : byName.keySet()) {
                if (k.toString().contains(f)) {
                    String line = setting(k.toString());
                    if (line != null) out.add(line);
                }
            }
        } catch (Throwable ignored) {
        }
        java.util.Collections.sort(out);
        return out;
    }

    public static void cancel() {
        try {
            Object behavior = call(primary(), "getPathingBehavior");
            call(behavior, "cancelEverything");
        } catch (Throwable ignored) {
        }
    }
}
