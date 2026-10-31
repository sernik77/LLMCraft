package dev.silverx.llmcraft.bridge.jsmacros;

import com.google.gson.JsonObject;
import com.jsmacrosce.jsmacros.core.Core;
import com.jsmacrosce.jsmacros.core.config.ScriptTrigger;
import com.jsmacrosce.jsmacros.core.event.impl.EventCustom;
import com.jsmacrosce.jsmacros.core.language.BaseScriptContext;
import com.jsmacrosce.jsmacros.core.service.ServiceManager;
import dev.silverx.llmcraft.bridge.BridgeConfig;
import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.game.ChatLog;
import dev.silverx.llmcraft.tool.Args;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolRegistry;
import dev.silverx.llmcraft.tool.ToolResult;
import dev.silverx.llmcraft.tool.impl.ChatTools;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/** The "jsmacros" tool: the player's JsMacros CE macros, services and scripts. */
public final class JsMacrosTool {
    private static final Set<String> READ_ONLY = Set.of("list", "read", "running");
    private static final long MAX_FILE_BYTES = 64 * 1024;

    private JsMacrosTool() {}

    public static void register() {
        ToolRegistry.register(Tool.builder("jsmacros", Category.SCRIPTS,
                        "JsMacros (script mod): the player's own macros and scripts. Actions: 'list' (macros with their key/event, services, "
                                + "script files, running scripts), 'read' (a script's source, to learn what it does), "
                                + "'run' (run a script file and wait; it gets args via event.getString('args') or LLM.getArgs(), and hands a result back "
                                + "with LLM.setResult(x) or event.putString('result', x); its chat output is returned too), "
                                + "'running', 'stop' (file name, or 'all' for every script you started), "
                                + "'event' (fire a custom event: every macro bound to that event name runs), "
                                + "'enable'/'disable' (a macro, by file), 'service_start'/'service_stop'/'service_restart' (by service name)"
                                + (BridgeConfig.get().jsmacros.allowInlineCode ? ", 'code' (run your own code; language default js; the player approves each time)" : "")
                                + ". Prefer the player's existing scripts.")
                .enumParam("action", "What to do", true, "list", "read", "run", "running", "stop", "event", "enable", "disable",
                        "service_start", "service_stop", "service_restart", "code")
                .param("file", "string", "Script path relative to the macro folder (read/run/stop/enable/disable)", false)
                .param("args", "string", "Text passed to the script or event (run/event/code)", false)
                .param("name", "string", "Event name (event) or service name (service_*)", false)
                .param("code", "string", "Source code (code)", false)
                .param("language", "string", "Language/file extension for code (default js)", false)
                .param("wait", "boolean", "run/code: wait for the script to finish (default true)", false)
                .param("timeout_seconds", "number", "Max wait (default from bridge.json, 60)", false)
                .available(ctx -> JsMacrosBridge.ready())
                .alsoOfferedUnder(Category.MODS)
                .categoryFor((ctx, a) -> READ_ONLY.contains(Args.str(a, "action", "")) ? Category.MODS : Category.SCRIPTS)
                .alwaysAsk((ctx, a) -> Args.str(a, "action", "").equals("code"))
                .summary(JsMacrosTool::summary)
                .deny((ctx, a) -> deny(a))
                .async((ctx, a) -> run(a))
                .build());
    }

    static String summary(JsonObject a) {
        String action = Args.str(a, "action", "");
        String args = Args.str(a, "args", "");
        return switch (action) {
            case "code" -> "jsmacros RUN CODE (" + Args.str(a, "language", "js") + "): " + cut(Args.str(a, "code", ""), 400);
            case "run" -> "jsmacros run " + Args.str(a, "file", "") + (args.isEmpty() ? "" : " (args: " + cut(args, 80) + ")");
            case "event" -> "jsmacros fire event " + Args.str(a, "name", "") + (args.isEmpty() ? "" : " (args: " + cut(args, 80) + ")");
            case "service_start", "service_stop", "service_restart" -> "jsmacros " + action.replace('_', ' ') + " " + Args.str(a, "name", "");
            default -> "jsmacros " + action + (Args.str(a, "file", "").isEmpty() ? "" : " " + Args.str(a, "file", ""));
        };
    }

    static String deny(JsonObject a) {
        String action = Args.str(a, "action", "");
        switch (action) {
            case "read", "run", "enable", "disable" -> {
                if (Args.str(a, "file", "").isBlank()) return "file is required";
            }
            case "stop" -> {
                if (Args.str(a, "file", "").isBlank()) return "file is required (or 'all')";
            }
            case "event", "service_start", "service_stop", "service_restart" -> {
                if (Args.str(a, "name", "").isBlank()) return "name is required";
            }
            case "code" -> {
                if (!BridgeConfig.get().jsmacros.allowInlineCode) {
                    return "running your own code is off (the player can allow it with jsmacros.allowInlineCode in config/llmcraft/bridge.json); use the player's scripts";
                }
                if (Args.str(a, "code", "").isBlank()) return "code is required";
            }
            case "list", "running" -> {
            }
            default -> {
                return "unknown action '" + action + "'";
            }
        }
        if (action.equals("event")) {
            String n = Args.str(a, "name", "");
            // Built-in events carry their own data; a fake one would break the macros bound to it.
            if (JsMacrosBridge.core().eventRegistry.events.contains(n) && !n.equals(JsMacrosBridge.CALL_EVENT)) return "'" + n + "' is a built-in JsMacros event; only custom event names can be fired";
        }
        return null;
    }

    static CompletableFuture<ToolResult> run(JsonObject a) {
        Core<?, ?> core = JsMacrosBridge.core();
        String action = Args.str(a, "action", "");
        try {
            return switch (action) {
                case "list" -> done(ToolResult.ok(list(core)));
                case "read" -> {
                    Path p = resolve(core, Args.str(a, "file", ""));
                    if (Files.size(p) > MAX_FILE_BYTES) yield done(ToolResult.error("file too large (" + Files.size(p) + " bytes)"));
                    yield done(ToolResult.ok(Files.readString(p, StandardCharsets.UTF_8)));
                }
                case "run" -> execute(core, resolve(core, Args.str(a, "file", "")), null, null, a);
                case "code" -> execute(core, null, Args.str(a, "code", ""), Args.str(a, "language", "js"), a);
                case "running" -> done(ToolResult.ok(running(core)));
                case "stop" -> done(stop(core, Args.str(a, "file", "")));
                case "event" -> {
                    String name = Args.str(a, "name", "");
                    int listeners = core.eventRegistry.getListeners(name).size();
                    EventCustom e = new EventCustom(core, name);
                    e.putString("args", Args.str(a, "args", ""));
                    core.threadPool.runTask(e::trigger);
                    yield done(ToolResult.ok("fired event " + name + " (" + listeners + " macro(s) bound to it)"));
                }
                case "enable", "disable" -> done(setEnabled(core, Args.str(a, "file", ""), action.equals("enable")));
                case "service_start", "service_stop", "service_restart" -> {
                    String name = Args.str(a, "name", "");
                    if (!core.services.getServices().contains(name)) yield done(ToolResult.error("no service '" + name + "'. Services: " + core.services.getServices()));
                    ServiceManager.ServiceStatus s = switch (action) {
                        case "service_start" -> core.services.startService(name);
                        case "service_stop" -> core.services.stopService(name);
                        default -> core.services.restartService(name);
                    };
                    yield done(ToolResult.ok("service " + name + ": " + s.name().toLowerCase(Locale.ROOT)));
                }
                default -> done(ToolResult.error("unknown action '" + action + "'"));
            };
        } catch (Exception e) {
            return done(ToolResult.error(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private static CompletableFuture<ToolResult> execute(Core<?, ?> core, Path file, String code, String language, JsonObject a) {
        boolean wait = Args.bool(a, "wait", true);
        int timeout = (int) Math.max(1, Args.num(a, "timeout_seconds", BridgeConfig.get().jsmacros.runTimeoutSeconds));
        String what = file != null ? core.config.macroFolder.toPath().relativize(file).toString() : "inline " + language + " code";
        long started = System.currentTimeMillis();
        if (!wait) {
            JsMacrosBridge.start(file, code, language, Args.str(a, "args", ""), false);
            return done(ToolResult.ok("started " + what + " (running in the background; 'running' / 'stop' to manage it)"));
        }
        // Scripts print with Chat.log, which has no mod prefix: record every chat line while it runs.
        ChatLog.captureAll(true);
        JsMacrosBridge.Run run;
        try {
            run = JsMacrosBridge.start(file, code, language, Args.str(a, "args", ""), true);
        } catch (RuntimeException e) {
            ChatLog.captureAll(false);
            throw e;
        }
        return run.finished().orTimeout(timeout, TimeUnit.SECONDS).handle((error, t) -> {
            ChatLog.captureAll(false);
            boolean timedOut = t != null && (t instanceof TimeoutException || t.getCause() instanceof TimeoutException);
            if (timedOut && !run.context().isContextClosed()) {
                run.context().closeContext();
                JsMacrosBridge.AI_RUNS.remove(run.context());
            }
            return new Object[]{error, timedOut};
        }).thenCompose(o -> ChatTools.collectFeedback(started, 150).thenApply(chat -> {
            String error = (String) o[0];
            boolean timedOut = (Boolean) o[1];
            String result = run.event().getString("result");
            long ms = System.currentTimeMillis() - started;
            StringBuilder sb = new StringBuilder();
            if (timedOut) sb.append(what).append(" was still running after ").append(timeout).append(" s and was stopped");
            else if (error != null) sb.append(what).append(" failed: ").append(error);
            else sb.append(what).append(" finished in ").append(String.format(Locale.ROOT, "%.1f", ms / 1000.0)).append(" s");
            if (result != null) sb.append("\nresult: ").append(result);
            if (!chat.isEmpty()) sb.append("\nchat output:\n").append(chat);
            if (!timedOut && error == null && run.context().shouldKeepAlive() && !run.context().isContextClosed()) {
                sb.append("\n(it keeps running in the background with event listeners)");
            }
            return timedOut || error != null ? ToolResult.error(sb.toString()) : ToolResult.ok(sb.toString());
        }));
    }

    static String list(Core<?, ?> core) throws java.io.IOException {
        Path root = core.config.macroFolder.toPath();
        StringBuilder sb = new StringBuilder();
        sb.append("Macro folder: ").append(root.toAbsolutePath()).append('\n');
        List<ScriptTrigger> triggers = core.eventRegistry.getScriptTriggers();
        sb.append("Macros (profile ").append(core.profile.getCurrentProfileName()).append("):\n");
        if (triggers.isEmpty()) sb.append("  none\n");
        for (ScriptTrigger t : triggers) {
            String kind = t.triggerType == ScriptTrigger.TriggerType.EVENT ? "on event " + t.event : "on key " + t.event + " (" + t.triggerType.name().toLowerCase(Locale.ROOT) + ")";
            sb.append("  ").append(t.getScriptFile()).append(" - ").append(kind).append(t.enabled ? "" : " [disabled]").append('\n');
        }
        var services = core.services.getServices();
        sb.append("Services:").append(services.isEmpty() ? " none" : "").append('\n');
        for (String s : services) {
            var trigger = core.services.getTrigger(s);
            sb.append("  ").append(s).append(" -> ").append(trigger == null ? "?" : root.relativize(trigger.file.toAbsolutePath()).toString())
                    .append(core.services.isRunning(s) ? " [running]" : " [stopped]").append(core.services.isEnabled(s) ? "" : " [disabled]").append('\n');
        }
        List<String> files = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> st = Files.walk(root, 5)) {
                st.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                        .filter(p -> !p.startsWith(".") && !p.contains("/.") && !p.contains("node_modules/") && !p.startsWith("llmcraft-inline."))
                        .sorted().limit(150).forEach(files::add);
            }
        }
        sb.append("Script files: ").append(files.isEmpty() ? "none" : String.join(", ", files)).append('\n');
        String running = running(core);
        if (!running.startsWith("no ")) sb.append(running).append('\n');
        sb.append("Custom events macros can bind to: ").append(String.join(", ", JsMacrosBridge.EVENTS));
        return sb.toString().trim();
    }

    static String running(Core<?, ?> core) {
        List<String> lines = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (BaseScriptContext<?> c : core.getContexts()) {
            if (c.isContextClosed()) continue;
            String f = name(core, c.getFile());
            String ev = c.getTriggeringEvent() == null ? "" : " on " + eventName(c);
            lines.add("  " + f + ev + ", " + (now - c.startTime) / 1000 + " s" + (JsMacrosBridge.AI_RUNS.contains(c) ? " [started by you]" : ""));
        }
        return lines.isEmpty() ? "no scripts running" : "Running scripts:\n" + String.join("\n", lines);
    }

    static ToolResult stop(Core<?, ?> core, String file) {
        if (file.equalsIgnoreCase("all")) return ToolResult.ok("stopped " + JsMacrosBridge.stopAiRuns() + " script(s) you started");
        int n = 0;
        for (BaseScriptContext<?> c : List.copyOf(core.getContexts())) {
            if (c.isContextClosed() || !matches(name(core, c.getFile()), file)) continue;
            c.closeContext();
            JsMacrosBridge.AI_RUNS.remove(c);
            n++;
        }
        return n == 0 ? ToolResult.error("no running script matches '" + file + "'") : ToolResult.ok("stopped " + n + " script(s)");
    }

    static ToolResult setEnabled(Core<?, ?> core, String file, boolean enabled) {
        var registry = core.eventRegistry;
        int n = 0;
        for (ScriptTrigger t : List.copyOf(registry.getScriptTriggers())) {
            if (!matches(t.getScriptFile(), file) || t.enabled == enabled) continue;
            ScriptTrigger changed = t.copy();
            changed.enabled = enabled;
            registry.removeScriptTrigger(t);
            registry.addScriptTrigger(changed);
            n++;
        }
        if (n == 0) return ToolResult.error("no macro for '" + file + "' that is not already " + (enabled ? "enabled" : "disabled"));
        core.profile.saveProfile();
        return ToolResult.ok((enabled ? "enabled " : "disabled ") + n + " macro(s) for " + file);
    }

    /** A path inside the macro folder that exists. */
    static Path resolve(Core<?, ?> core, String rel) {
        Path root = core.config.macroFolder.toPath().toAbsolutePath().normalize();
        Path p = root.resolve(rel.replace('\\', '/')).toAbsolutePath().normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("path outside the macro folder");
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("no script '" + rel + "' (see action list)");
        return p;
    }

    static boolean matches(String path, String wanted) {
        if (path == null) return false;
        String p = path.replace('\\', '/');
        String w = wanted.replace('\\', '/');
        return p.equals(w) || p.endsWith("/" + w) || new File(p).getName().equals(w);
    }

    static String name(Core<?, ?> core, File f) {
        if (f == null) return "?";
        try {
            return core.config.macroFolder.toPath().toAbsolutePath().relativize(f.toPath().toAbsolutePath()).toString().replace('\\', '/');
        } catch (Exception e) {
            return f.getName();
        }
    }

    static String eventName(BaseScriptContext<?> c) {
        var e = c.getTriggeringEvent();
        if (e instanceof EventCustom ec) return ec.eventName;
        try {
            return e.getEventName();
        } catch (Exception ex) {
            return e.getClass().getSimpleName();
        }
    }

    static String cut(String s, int max) {
        s = s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static CompletableFuture<ToolResult> done(ToolResult r) {
        return CompletableFuture.completedFuture(r);
    }
}
