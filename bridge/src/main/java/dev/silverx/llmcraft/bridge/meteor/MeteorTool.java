package dev.silverx.llmcraft.bridge.meteor;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import dev.silverx.llmcraft.bridge.BridgeConfig;
import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.tool.Args;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolRegistry;
import dev.silverx.llmcraft.tool.ToolResult;
import dev.silverx.llmcraft.tool.impl.ChatTools;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** The "meteor" tool: Meteor Client modules, their settings and Meteor's own commands, straight through Meteor's API. */
public final class MeteorTool {
    /** Our own Meteor commands: the AI must not call itself. */
    static final Set<String> SELF = Set.of("ai", "llm");
    private static final Set<String> READ_ONLY = Set.of("modules", "info", "commands");
    private static final int MAX_VALUE = 160;

    private MeteorTool() {}

    public static void register() {
        ToolRegistry.register(Tool.builder("meteor", Category.CLIENT_COMMANDS,
                        "Meteor Client (utility mod): its modules, their settings and Meteor commands. Actions: "
                                + "'modules' (list; filter = text, a category name or 'active'), 'info' (a module's description and settings with values), "
                                + "'enable'/'disable'/'toggle' (a module), 'set' (module + setting + value, Meteor's own format: numbers, true/false, "
                                + "enum names, comma-separated lists like 'stone,dirt'), 'reset' (a setting back to default), "
                                + "'commands' (list Meteor commands; filter = a command name for its full usage), "
                                + "'command' (run a Meteor command without the prefix, e.g. 'friends add Steve', 'macro <name>', 'waypoint list'). "
                                + "Module names are Meteor's ids like 'kill-aura', 'auto-totem', 'fullbright'.")
                .enumParam("action", "What to do", true, "modules", "info", "enable", "disable", "toggle", "set", "reset", "commands", "command")
                .param("module", "string", "Module name (info/enable/disable/toggle/set/reset)", false)
                .param("setting", "string", "Setting name (set/reset)", false)
                .param("value", "string", "New value (set)", false)
                .param("text", "string", "Command text without the prefix (command)", false)
                .param("filter", "string", "Optional filter (modules/commands)", false)
                .alsoOfferedUnder(Category.MODS)
                .categoryFor((ctx, a) -> {
                    String action = Args.str(a, "action", "");
                    if (READ_ONLY.contains(action)) return Category.MODS;
                    if (action.equals("command") && root(Args.str(a, "text", "")).equals("say")) return Category.CHAT_SEND;
                    return Category.CLIENT_COMMANDS;
                })
                .summary(MeteorTool::summary)
                .deny((ctx, a) -> deny(a))
                .async((ctx, a) -> run(a))
                .build());
    }

    static String summary(com.google.gson.JsonObject a) {
        String action = Args.str(a, "action", "");
        return switch (action) {
            case "command" -> "meteor " + prefix() + stripPrefix(Args.str(a, "text", ""));
            case "set" -> "meteor set " + Args.str(a, "module", "") + " " + Args.str(a, "setting", "") + " = " + Args.str(a, "value", "");
            case "reset" -> "meteor reset " + Args.str(a, "module", "") + " " + Args.str(a, "setting", "");
            case "modules", "commands" -> "meteor " + action + (Args.str(a, "filter", "").isEmpty() ? "" : " " + Args.str(a, "filter", ""));
            default -> "meteor " + action + " " + Args.str(a, "module", "");
        };
    }

    static String deny(com.google.gson.JsonObject a) {
        String action = Args.str(a, "action", "");
        BridgeConfig.Meteor cfg = BridgeConfig.get().meteor;
        switch (action) {
            case "command" -> {
                String text = stripPrefix(Args.str(a, "text", ""));
                if (text.isBlank()) return "text is required";
                String root = root(text);
                if (SELF.contains(root)) return "the AI cannot run its own .ai/.llm commands";
                for (String b : cfg.blockedCommands) if (b.equalsIgnoreCase(root)) return "Meteor command '" + root + "' is blocked in bridge.json (meteor.blockedCommands)";
                if (root.equals("toggle") || root.equals("t") || root.equals("settings") || root.equals("s")) {
                    // Same rules as the module actions.
                    String[] w = text.trim().split("\\s+");
                    if (w.length >= 2 && blocked(w[1])) return "module '" + w[1] + "' is blocked in bridge.json (meteor.blockedModules)";
                    if (w.length >= 2 && w[1].equalsIgnoreCase("all")) return "toggling every module at once is not allowed";
                }
            }
            case "info", "enable", "disable", "toggle", "set", "reset" -> {
                String m = Args.str(a, "module", "");
                if (m.isBlank()) return "module is required";
                if (!action.equals("info") && blocked(m)) return "module '" + m + "' is blocked in bridge.json (meteor.blockedModules)";
                if ((action.equals("set") || action.equals("reset")) && Args.str(a, "setting", "").isBlank()) return "setting is required";
                if (action.equals("set") && !a.has("value")) return "value is required";
            }
            case "modules", "commands" -> {
            }
            default -> {
                return "unknown action '" + action + "'";
            }
        }
        return null;
    }

    static CompletableFuture<ToolResult> run(com.google.gson.JsonObject a) {
        String action = Args.str(a, "action", "");
        try {
            return switch (action) {
                case "modules" -> done(ToolResult.ok(listModules(Args.str(a, "filter", ""))));
                case "info" -> done(withModule(a, MeteorTool::info));
                case "enable", "disable", "toggle" -> done(withModule(a, m -> {
                    boolean was = m.isActive();
                    if (action.equals("enable") && !was || action.equals("disable") && was || action.equals("toggle")) m.toggle();
                    boolean now = m.isActive();
                    String state = now ? "ON" : "OFF";
                    if (was == now && !action.equals("toggle")) return ToolResult.ok(m.name + " was already " + state);
                    if (was == now) return ToolResult.error(m.name + " stayed " + state + " (the module refused, e.g. it needs a world or another module)");
                    return ToolResult.ok(m.name + " is now " + state);
                }));
                case "set", "reset" -> done(withModule(a, m -> {
                    Setting<?> s = setting(m, Args.str(a, "setting", ""));
                    if (s == null) return ToolResult.error("module " + m.name + " has no setting '" + Args.str(a, "setting", "") + "'. Settings: " + settingNames(m));
                    String before = value(s);
                    if (action.equals("reset")) {
                        s.reset();
                        return ToolResult.ok(m.name + "." + s.name + ": " + before + " -> " + value(s) + " (default)");
                    }
                    String v = Args.str(a, "value", "");
                    if (!s.parse(v)) {
                        List<String> sug = suggestions(s);
                        return ToolResult.error("Meteor could not parse '" + v + "' for " + m.name + "." + s.name + " (still " + before + ")"
                                + (sug.isEmpty() ? "" : ". Valid values: " + String.join(", ", sug.size() > 40 ? sug.subList(0, 40) : sug)));
                    }
                    String after = value(s);
                    if (after.equals(before)) return ToolResult.ok(m.name + "." + s.name + " is " + after + " (unchanged: the same value, or outside the allowed range)");
                    return ToolResult.ok(m.name + "." + s.name + ": " + before + " -> " + after);
                }));
                case "commands" -> done(ToolResult.ok(listCommands(Args.str(a, "filter", ""))));
                case "command" -> command(stripPrefix(Args.reqStr(a, "text")));
                default -> done(ToolResult.error("unknown action '" + action + "'"));
            };
        } catch (Exception e) {
            return done(ToolResult.error(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private static CompletableFuture<ToolResult> command(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return done(ToolResult.error("Meteor commands need a world/server"));
        long before = System.currentTimeMillis();
        try {
            Commands.dispatch(text);
        } catch (CommandSyntaxException e) {
            String usage = usage(root(text));
            return done(ToolResult.error("Meteor: " + e.getMessage() + (usage.isEmpty() ? "" : "\nusage:\n" + usage)));
        }
        return ChatTools.collectFeedback(before, 800).thenApply(fb -> ToolResult.ok("ran " + prefix() + text + (fb.isEmpty() ? "" : "\nfeedback:\n" + fb)));
    }

    // ---- modules -------------------------------------------------------------------------------

    static String listModules(String filter) {
        String f = filter.trim().toLowerCase(Locale.ROOT);
        Map<String, List<Module>> byCat = new LinkedHashMap<>();
        for (var cat : Modules.loopCategories()) byCat.put(cat.name, new ArrayList<>());
        boolean detailed = !f.isEmpty();
        int n = 0;
        for (Module m : Modules.get().getAll()) {
            boolean match = f.isEmpty() || f.equals("active") && m.isActive() || m.category.name.equalsIgnoreCase(f)
                    || (m.name + " " + m.title + " " + String.join(" ", m.aliases) + " " + m.description).toLowerCase(Locale.ROOT).contains(f);
            if (!match) continue;
            byCat.computeIfAbsent(m.category.name, k -> new ArrayList<>()).add(m);
            n++;
        }
        if (n == 0) {
            var best = Modules.get().searchTitles(filter).stream().limit(5).map(p -> p.getA().name).toList();
            return "no module matches '" + filter + "'. Closest: " + String.join(", ", best);
        }
        StringBuilder sb = new StringBuilder(n + " modules (* = on):\n");
        for (var e : byCat.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            e.getValue().sort((x, y) -> x.name.compareTo(y.name));
            if (detailed) {
                sb.append(e.getKey()).append(":\n");
                for (Module m : e.getValue()) sb.append("  ").append(m.isActive() ? "* " : "  ").append(m.name).append(" - ").append(cut(m.description, 110)).append('\n');
            } else {
                sb.append(e.getKey()).append(": ");
                List<String> names = new ArrayList<>();
                for (Module m : e.getValue()) names.add(m.isActive() ? m.name + "*" : m.name);
                sb.append(String.join(", ", names)).append('\n');
            }
        }
        if (!detailed) sb.append("Use filter (a word, a category, or 'active') for descriptions, and info for a module's settings.");
        return sb.toString().trim();
    }

    static ToolResult info(Module m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.title).append(" (").append(m.name).append(") [").append(m.category.name).append("] ").append(m.isActive() ? "ON" : "OFF").append('\n');
        sb.append(m.description).append('\n');
        if (m.aliases.length > 0) sb.append("aliases: ").append(String.join(", ", m.aliases)).append('\n');
        if (m.keybind.isSet()) sb.append("keybind: ").append(m.keybind).append('\n');
        String extra = m.getInfoString();
        if (extra != null && !extra.isBlank()) sb.append("status: ").append(extra).append('\n');
        for (SettingGroup g : m.settings) {
            sb.append("[").append(g.name).append("]\n");
            for (Setting<?> s : g) {
                sb.append("  ").append(s.name).append(" = ").append(value(s));
                if (!s.isVisible()) sb.append(" (hidden now)");
                sb.append(" - ").append(cut(s.description, 100));
                List<String> sug = suggestions(s);
                if (!sug.isEmpty() && sug.size() <= 12) sb.append(" {").append(String.join("|", sug)).append('}');
                sb.append('\n');
            }
        }
        return ToolResult.ok(sb.toString().trim());
    }

    private interface ModuleAction {
        ToolResult run(Module m);
    }

    private static ToolResult withModule(com.google.gson.JsonObject a, ModuleAction action) {
        String name = Args.str(a, "module", "");
        Module m = module(name);
        if (m == null) {
            var best = Modules.get().searchTitles(name).stream().limit(5).map(p -> p.getA().name).toList();
            return ToolResult.error("no Meteor module '" + name + "'. Closest: " + String.join(", ", best));
        }
        return action.run(m);
    }

    /** Exact match on name, title or alias, ignoring case, spaces and dashes. */
    public static Module module(String name) {
        String n = normalize(name);
        if (n.isEmpty()) return null;
        for (Module m : Modules.get().getAll()) {
            if (normalize(m.name).equals(n) || normalize(m.title).equals(n)) return m;
            for (String alias : m.aliases) if (normalize(alias).equals(n)) return m;
        }
        return null;
    }

    static boolean blocked(String name) {
        Module m = module(name);
        for (String b : BridgeConfig.get().meteor.blockedModules) {
            if (normalize(b).equals(normalize(name)) || m != null && normalize(b).equals(normalize(m.name))) return true;
        }
        return false;
    }

    static Setting<?> setting(Module m, String name) {
        String n = normalize(name);
        for (SettingGroup g : m.settings) {
            for (Setting<?> s : g) if (normalize(s.name).equals(n) || normalize(s.title).equals(n)) return s;
        }
        return null;
    }

    static String settingNames(Module m) {
        List<String> out = new ArrayList<>();
        for (SettingGroup g : m.settings) for (Setting<?> s : g) out.add(s.name);
        return String.join(", ", out);
    }

    static String value(Setting<?> s) {
        Object v = s.get();
        return cut(v == null ? "null" : v.toString(), MAX_VALUE);
    }

    static List<String> suggestions(Setting<?> s) {
        try {
            List<String> sug = s.getSuggestions();
            return sug == null ? List.of() : sug;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---- commands ------------------------------------------------------------------------------

    static String listCommands(String filter) {
        String f = stripPrefix(filter).trim().toLowerCase(Locale.ROOT);
        if (!f.isEmpty()) {
            Command c = Commands.get(f);
            if (c == null) for (Command x : Commands.COMMANDS) if (x.getAliases().contains(f)) c = x;
            if (c != null) {
                String usage = usage(c.getName());
                return prefix() + c.getName() + (c.getAliases().isEmpty() ? "" : " (aliases: " + String.join(", ", c.getAliases()) + ")") + " - " + c.getDescription()
                        + (usage.isEmpty() ? "" : "\nusage:\n" + usage);
            }
        }
        StringBuilder sb = new StringBuilder("Meteor commands (prefix '" + prefix() + "'; the 'command' action takes them without it):\n");
        for (Command c : Commands.COMMANDS) {
            if (SELF.contains(c.getName())) continue;
            String line = c.getName() + " - " + cut(c.getDescription(), 90);
            if (!f.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(f)) continue;
            sb.append(line).append('\n');
        }
        sb.append("Pass a command name as filter for its full usage.");
        return sb.toString().trim();
    }

    static String usage(String root) {
        CommandNode<SharedSuggestionProvider> node = Commands.DISPATCHER.getRoot().getChild(root);
        var conn = Minecraft.getInstance().getConnection();
        if (node == null || conn == null) return "";
        String[] all = Commands.DISPATCHER.getAllUsage(node, conn.getSuggestionsProvider(), true);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < all.length && i < 30; i++) lines.add(prefix() + root + " " + all[i]);
        return String.join("\n", lines);
    }

    // ---- helpers -------------------------------------------------------------------------------

    static String prefix() {
        try {
            return Config.get().prefix.get();
        } catch (Exception e) {
            return ".";
        }
    }

    static String stripPrefix(String text) {
        String t = text == null ? "" : text.trim();
        String p = prefix();
        if (!p.isEmpty() && t.startsWith(p)) t = t.substring(p.length());
        return t.trim();
    }

    static String root(String text) {
        String t = stripPrefix(text);
        return t.isEmpty() ? "" : t.split("\\s+")[0].toLowerCase(Locale.ROOT);
    }

    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static String cut(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static CompletableFuture<ToolResult> done(ToolResult r) {
        return CompletableFuture.completedFuture(r);
    }
}
