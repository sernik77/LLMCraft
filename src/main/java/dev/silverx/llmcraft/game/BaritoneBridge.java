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

    public static void cancel() {
        try {
            Object behavior = call(primary(), "getPathingBehavior");
            call(behavior, "cancelEverything");
        } catch (Throwable ignored) {
        }
    }
}
