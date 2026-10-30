package dev.silverx.llmcraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.silverx.llmcraft.agent.Agent;
import dev.silverx.llmcraft.agent.Approvals;
import dev.silverx.llmcraft.agent.Permissions;
import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.config.ConfigEditor;
import dev.silverx.llmcraft.config.ConfigManager;
import dev.silverx.llmcraft.config.PermissionMode;
import dev.silverx.llmcraft.data.MemoryStore;
import dev.silverx.llmcraft.data.Stores;
import dev.silverx.llmcraft.data.WorldStore;
import dev.silverx.llmcraft.game.Output;
import dev.silverx.llmcraft.llm.ChatRequest;
import dev.silverx.llmcraft.llm.Message;
import dev.silverx.llmcraft.llm.ProviderProfile;
import dev.silverx.llmcraft.llm.Providers;
import dev.silverx.llmcraft.llm.ToolSpec;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.nio.file.Path;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** /ai <prompt> and /llm <subcommand>. */
public final class Commands {
    private static final SuggestionProvider<FabricClientCommandSource> CATEGORIES = (c, b) -> {
        for (Category cat : Category.values()) b.suggest(cat.name().toLowerCase(Locale.ROOT));
        return b.buildFuture();
    };
    private static final SuggestionProvider<FabricClientCommandSource> MODES = (c, b) -> {
        for (PermissionMode m : PermissionMode.values()) b.suggest(m.name().toLowerCase(Locale.ROOT));
        return b.buildFuture();
    };
    private static final SuggestionProvider<FabricClientCommandSource> PROFILES = (c, b) -> {
        for (ProviderProfile p : ConfigManager.get().profiles) b.suggest(p.name);
        return b.buildFuture();
    };
    private static final SuggestionProvider<FabricClientCommandSource> SCOPES = (c, b) -> b.suggest("world").suggest("global").buildFuture();

    private Commands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(literal("ai")
                .then(argument("prompt", StringArgumentType.greedyString()).executes(c -> {
                    Agent.submit(StringArgumentType.getString(c, "prompt"), Agent.Source.USER);
                    return 1;
                }))
                .executes(c -> {
                    Output.info("Usage: /ai <message>   ·   settings: /llm");
                    return 1;
                }));

        d.register(literal("llm")
                .executes(c -> help())
                .then(literal("help").executes(c -> help()))
                .then(literal("stop").executes(c -> {
                    Agent.cancel("stopped by player");
                    Output.info("Stopped.");
                    return 1;
                }))
                .then(literal("status").executes(c -> status()))
                .then(literal("enable").executes(c -> setEnabled(true)))
                .then(literal("disable").executes(c -> setEnabled(false)))
                .then(literal("reload").executes(c -> {
                    ConfigManager.load();
                    Stores.reset();
                    Output.info("Config reloaded from " + ConfigManager.configFile());
                    return 1;
                }))
                .then(literal("test").executes(c -> test()))
                .then(literal("tools").executes(c -> tools()))
                .then(literal("folder").executes(c -> {
                    Output.show(Output.header().append(link("Open config folder", ConfigManager.dir())));
                    return 1;
                }))
                .then(literal("profiles").executes(c -> profiles()))
                .then(literal("profile")
                        .executes(c -> profiles())
                        .then(argument("name", StringArgumentType.word()).suggests(PROFILES).executes(c -> {
                            String name = StringArgumentType.getString(c, "name");
                            ProviderProfile p = findProfile(name);
                            if (p == null) return fail("No profile '" + name + "'. See /llm profiles");
                            ConfigManager.get().activeProfile = p.name;
                            ConfigManager.save();
                            Output.info("Active profile: " + p.name + " (" + Providers.create(p).describe() + ")");
                            return 1;
                        })))
                .then(literal("model").then(argument("model", StringArgumentType.greedyString()).executes(c -> {
                    ProviderProfile p = ConfigManager.get().activeProfile();
                    if (p == null) return fail("No active profile");
                    p.model = StringArgumentType.getString(c, "model").trim();
                    ConfigManager.save();
                    Output.info("Model of profile '" + p.name + "' set to " + p.model);
                    return 1;
                })))
                .then(literal("perms").executes(c -> perms()))
                .then(literal("perm").then(argument("category", StringArgumentType.word()).suggests(CATEGORIES)
                        .then(argument("mode", StringArgumentType.word()).suggests(MODES)
                                .executes(c -> perm(c, "auto"))
                                .then(literal("world").executes(c -> perm(c, "world")))
                                .then(literal("singleplayer").executes(c -> perm(c, "sp")))
                                .then(literal("multiplayer").executes(c -> perm(c, "mp")))
                                .then(literal("all").executes(c -> perm(c, "all"))))))
                .then(literal("approve").then(argument("id", StringArgumentType.word())
                        .executes(c -> approve(c, false))
                        .then(literal("session").executes(c -> approve(c, true)))))
                .then(literal("deny").then(argument("id", StringArgumentType.word()).executes(c -> {
                    if (!Approvals.deny(StringArgumentType.getString(c, "id"))) return fail("No such pending request (expired?)");
                    return 1;
                })))
                .then(literal("memory")
                        .executes(c -> memoryList("world"))
                        .then(argument("scope", StringArgumentType.word()).suggests(SCOPES)
                                .executes(c -> memoryList(StringArgumentType.getString(c, "scope")))
                                .then(literal("add").then(argument("text", StringArgumentType.greedyString()).executes(c -> {
                                    var e = memory(StringArgumentType.getString(c, "scope")).add(StringArgumentType.getString(c, "text"), "player");
                                    Output.info("Saved #" + e.id);
                                    return 1;
                                })))
                                .then(literal("remove").then(argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                                    boolean ok = memory(StringArgumentType.getString(c, "scope")).remove(IntegerArgumentType.getInteger(c, "id"));
                                    return ok ? ok("Removed.") : fail("No such entry");
                                })))
                                .then(literal("clear").executes(c -> {
                                    memory(StringArgumentType.getString(c, "scope")).clear();
                                    return ok("Memory cleared.");
                                }))))
                .then(literal("history")
                        .then(literal("clear").executes(c -> {
                            Stores.current().clearHistory();
                            return ok("History and summary of this world/server cleared.");
                        }))
                        .then(literal("show").executes(c -> {
                            WorldStore w = Stores.current();
                            if (!w.summary().isBlank()) Output.info("Summary: " + w.summary());
                            var h = w.history();
                            for (int i = Math.max(0, h.size() - 10); i < h.size(); i++) Output.info(h.get(i).role + ": " + abbreviate(h.get(i).text, 200));
                            if (h.isEmpty()) Output.info("(no history)");
                            return 1;
                        })))
                .then(literal("instructions").executes(c -> {
                    WorldStore w = Stores.current();
                    Output.show(Output.header().append(link("Global instructions", ConfigManager.globalInstructionsFile()))
                            .append("  ").append(link("This " + (w.multiplayer ? "server" : "world") + "'s instructions", w.instructionsFile()))
                            .append("  ").append(link("Folder", w.dir)));
                    Output.info("Edit the files, they are read on every request.");
                    return 1;
                }))
                .then(literal("triggers")
                        .then(literal("on").executes(c -> triggers(true, false)))
                        .then(literal("off").executes(c -> triggers(false, false)))
                        .then(literal("world")
                                .then(literal("on").executes(c -> triggers(true, true)))
                                .then(literal("off").executes(c -> triggers(false, true)))
                                .then(literal("inherit").executes(c -> {
                                    WorldStore w = Stores.current();
                                    w.settings().triggersEnabled = null;
                                    w.saveSettings();
                                    return ok("Triggers here follow the global setting.");
                                }))))
                .then(literal("world")
                        .executes(c -> worldInfo())
                        .then(literal("enable").executes(c -> worldEnabled(true)))
                        .then(literal("disable").executes(c -> worldEnabled(false)))
                        .then(literal("profile").then(argument("name", StringArgumentType.word()).suggests(PROFILES).executes(c -> {
                            String name = StringArgumentType.getString(c, "name");
                            WorldStore w = Stores.current();
                            if (name.equalsIgnoreCase("default")) w.settings().profile = "";
                            else if (findProfile(name) == null) return fail("No profile '" + name + "'");
                            else w.settings().profile = findProfile(name).name;
                            w.saveSettings();
                            return ok("Profile here: " + (w.settings().profile.isEmpty() ? "default (global active)" : w.settings().profile));
                        }))))
                .then(literal("config")
                        .then(literal("get").executes(c -> configGet(""))
                                .then(argument("path", StringArgumentType.greedyString()).executes(c -> configGet(StringArgumentType.getString(c, "path")))))
                        .then(literal("set").then(argument("path", StringArgumentType.word())
                                .then(argument("value", StringArgumentType.greedyString()).executes(c -> {
                                    String path = StringArgumentType.getString(c, "path");
                                    try {
                                        ConfigEditor.set(path, StringArgumentType.getString(c, "value"));
                                        return ok(path + " = " + ConfigEditor.get(path));
                                    } catch (Exception e) {
                                        return fail(e.getMessage());
                                    }
                                }))))));
    }

    // ------------------------------------------------------------------------------------------

    private static int help() {
        String[] lines = {
                "/ai <message> — talk to the AI (or type " + ConfigManager.get().chat.promptPrefix + " <message> in chat)",
                "/llm stop · status · test · tools · reload · enable/disable · folder",
                "/llm profiles · profile <name> · model <model>",
                "/llm perms · perm <category> <off|ask|allow> [world|singleplayer|multiplayer|all]",
                "/llm memory [world|global] [add <text>|remove <id>|clear]",
                "/llm history show|clear · instructions · triggers on|off · triggers world on|off|inherit",
                "/llm world [enable|disable|profile <name|default>]",
                "/llm config get [path] · config set <path> <value>   e.g. chat.promptPrefix @bot"};
        for (String l : lines) Output.info(l);
        return 1;
    }

    private static int status() {
        var cfg = ConfigManager.get();
        WorldStore w = Stores.current();
        ProviderProfile p = !w.settings().profile.isBlank() ? cfg.profile(w.settings().profile) : cfg.activeProfile();
        Output.info("Enabled: " + cfg.enabled + (w.settings().enabled ? "" : " (disabled in this world)") + " · agent: " + Agent.status());
        Output.info("Provider: " + (p == null ? "none" : p.name + " → " + Providers.create(p).describe() + ", tools " + p.toolMode + (p.vision ? ", vision" : "")));
        Output.info("World: " + w.displayName + " [" + w.key + "] · memory " + w.memory.all().size() + " · global memory "
                + Stores.global().all().size() + " · history " + w.history().size());
        Output.info("Triggers: " + (w.settings().triggersEnabled != null ? w.settings().triggersEnabled + " (world)" : cfg.triggers.enabled)
                + " · pending approvals: " + Approvals.pendingCount() + " · tools offered: " + Agent.offeredTools().size());
        return 1;
    }

    private static int setEnabled(boolean on) {
        ConfigManager.get().enabled = on;
        ConfigManager.save();
        if (!on) Agent.cancel("disabled");
        return ok("LLMCraft " + (on ? "enabled" : "disabled"));
    }

    private static int test() {
        var cfg = ConfigManager.get();
        WorldStore w = Stores.current();
        ProviderProfile p = !w.settings().profile.isBlank() ? cfg.profile(w.settings().profile) : cfg.activeProfile();
        if (p == null) return fail("No profile");
        Output.info("Testing " + Providers.create(p).describe() + " …");
        long t0 = System.currentTimeMillis();
        ChatRequest req = new ChatRequest();
        req.messages.add(Message.system("You are a connectivity test."));
        req.messages.add(Message.user("Reply with the single word: pong"));
        Providers.create(p).chat(req).whenComplete((r, t) -> {
            if (t != null) Output.error("Failed: " + (t.getCause() != null ? t.getCause().getMessage() : t.getMessage()));
            else Output.info("OK in " + (System.currentTimeMillis() - t0) + " ms: \"" + abbreviate(r.text, 80) + "\"");
        });
        return 1;
    }

    private static int tools() {
        var specs = Agent.offeredTools();
        StringBuilder sb = new StringBuilder();
        for (ToolSpec s : specs) sb.append(s.name()).append(' ');
        Output.info(specs.size() + " tools offered: " + sb.toString().trim());
        return 1;
    }

    private static int profiles() {
        var cfg = ConfigManager.get();
        for (ProviderProfile p : cfg.profiles) {
            boolean active = p.name.equalsIgnoreCase(cfg.activeProfile);
            MutableComponent line = Output.header().append(Component.literal((active ? "▶ " : "  ") + p.name).withStyle(active ? ChatFormatting.GREEN : ChatFormatting.WHITE))
                    .append(Component.literal(" " + p.type + " " + p.model + " @ " + p.baseUrl).withStyle(ChatFormatting.GRAY));
            if (!active) line.append(" ").append(Output.button("use", ChatFormatting.AQUA, "/llm profile " + p.name, "Switch to " + p.name));
            Output.show(line);
        }
        return 1;
    }

    private static int perms() {
        WorldStore w = Stores.current();
        Output.info("Permissions here (" + (w.multiplayer ? "multiplayer" : "singleplayer") + ", * = world override). Click to cycle:");
        for (Category c : Category.values()) {
            PermissionMode m = Permissions.mode(c, w);
            boolean override = w.settings().permissions.containsKey(c);
            PermissionMode next = PermissionMode.values()[(m.ordinal() + 1) % PermissionMode.values().length];
            ChatFormatting color = m == PermissionMode.ALLOW ? ChatFormatting.GREEN : m == PermissionMode.ASK ? ChatFormatting.YELLOW : ChatFormatting.RED;
            Output.show(Component.literal("  " + c.name().toLowerCase(Locale.ROOT) + (override ? "*" : "") + " ").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(m.name()).withStyle(Style.EMPTY.withColor(color).withBold(true)
                            .withClickEvent(new ClickEvent.RunCommand("/llm perm " + c.name().toLowerCase(Locale.ROOT) + " " + next.name().toLowerCase(Locale.ROOT)))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Set to " + next + " (" + (w.multiplayer ? "multiplayer" : "singleplayer") + " default)")))))
                    .append(Component.literal("  " + c.description).withStyle(ChatFormatting.DARK_GRAY)));
        }
        return 1;
    }

    private static int perm(CommandContext<FabricClientCommandSource> c, String scope) {
        Category cat;
        PermissionMode mode;
        try {
            cat = Category.valueOf(StringArgumentType.getString(c, "category").toUpperCase(Locale.ROOT));
            mode = PermissionMode.valueOf(StringArgumentType.getString(c, "mode").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fail("Unknown category or mode. Categories: /llm perms; modes: off, ask, allow");
        }
        var cfg = ConfigManager.get();
        WorldStore w = Stores.current();
        switch (scope) {
            case "world" -> {
                w.settings().permissions.put(cat, mode);
                w.saveSettings();
            }
            case "sp" -> cfg.singleplayerPermissions.put(cat, mode);
            case "mp" -> cfg.multiplayerPermissions.put(cat, mode);
            case "all" -> {
                cfg.singleplayerPermissions.put(cat, mode);
                cfg.multiplayerPermissions.put(cat, mode);
            }
            default -> {
                if (w.settings().permissions.containsKey(cat)) {
                    w.settings().permissions.put(cat, mode);
                    w.saveSettings();
                    scope = "world";
                } else if (w.multiplayer) {
                    cfg.multiplayerPermissions.put(cat, mode);
                    scope = "multiplayer";
                } else {
                    cfg.singleplayerPermissions.put(cat, mode);
                    scope = "singleplayer";
                }
            }
        }
        ConfigManager.save();
        return ok(cat.name().toLowerCase(Locale.ROOT) + " → " + mode + " (" + scope + ")");
    }

    private static int approve(CommandContext<FabricClientCommandSource> c, boolean session) {
        if (!Approvals.approve(StringArgumentType.getString(c, "id"), session)) return fail("No such pending request (expired?)");
        return 1;
    }

    private static MemoryStore memory(String scope) {
        return "global".equalsIgnoreCase(scope) ? Stores.global() : Stores.current().memory;
    }

    private static int memoryList(String scope) {
        MemoryStore m = memory(scope);
        var all = m.all();
        Output.info((scope.equalsIgnoreCase("global") ? "Global" : "World") + " memory (" + all.size() + "):");
        for (var e : all) Output.info("#" + e.id + (e.tag.isEmpty() ? "" : " [" + e.tag + "]") + " " + e.text);
        return 1;
    }

    private static int triggers(boolean on, boolean world) {
        if (world) {
            WorldStore w = Stores.current();
            w.settings().triggersEnabled = on;
            w.saveSettings();
        } else {
            ConfigManager.get().triggers.enabled = on;
            ConfigManager.save();
        }
        return ok("Automatic triggers " + (on ? "ON" : "OFF") + (world ? " for this world/server" : " (global)"));
    }

    private static int worldInfo() {
        WorldStore w = Stores.current();
        Output.info(w.displayName + " [" + w.key + "] enabled=" + w.settings().enabled + " profile=" + (w.settings().profile.isBlank() ? "default" : w.settings().profile)
                + " overrides=" + w.settings().permissions);
        Output.show(Output.header().append(link("Open folder", w.dir)));
        return 1;
    }

    private static int worldEnabled(boolean on) {
        WorldStore w = Stores.current();
        w.settings().enabled = on;
        w.saveSettings();
        if (!on) Agent.cancel("disabled for world");
        return ok("AI " + (on ? "enabled" : "disabled") + " for " + w.displayName);
    }

    private static int configGet(String path) {
        try {
            String v = ConfigEditor.get(path);
            for (String line : v.split("\n")) Output.info(line);
            return 1;
        } catch (Exception e) {
            return fail(e.getMessage());
        }
    }

    private static ProviderProfile findProfile(String name) {
        for (ProviderProfile p : ConfigManager.get().profiles) if (p.name.equalsIgnoreCase(name)) return p;
        return null;
    }

    private static MutableComponent link(String label, Path path) {
        return Component.literal("[" + label + "]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenFile(path.toAbsolutePath().toString()))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(path.toAbsolutePath().toString()))));
    }

    private static int ok(String msg) {
        Output.info(msg);
        return 1;
    }

    private static int fail(String msg) {
        Output.error(msg);
        return 0;
    }

    private static String abbreviate(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
