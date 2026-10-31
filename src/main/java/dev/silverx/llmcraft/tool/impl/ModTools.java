package dev.silverx.llmcraft.tool.impl;

import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.config.ConfigManager;
import dev.silverx.llmcraft.game.BaritoneBridge;
import dev.silverx.llmcraft.game.Controller;
import dev.silverx.llmcraft.game.LogTail;
import dev.silverx.llmcraft.game.Mining;
import dev.silverx.llmcraft.game.Script;
import dev.silverx.llmcraft.tool.Args;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolResult;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ModTools {
    /** File names that look like they hold credentials are never shown to the model. */
    private static final Pattern SECRET_FILE = Pattern.compile("(?i).*(account|token|secret|password|passwd|credential|auth|session|cookie|\\.key$|\\.pem$).*");
    private static final long MAX_FILE_BYTES = 64 * 1024;

    private ModTools() {}

    public static void register(List<Tool> out) {
        out.add(Tool.builder("list_mods", Category.MODS, "List installed mods (id, name, version). Libraries/builtins are hidden unless include_libraries is true.")
                .param("filter", "string", "Optional text to match id/name/description", false)
                .param("include_libraries", "boolean", "Default false", false)
                .sync((ctx, a) -> {
                    String f = Args.str(a, "filter", "").toLowerCase(Locale.ROOT);
                    boolean libs = Args.bool(a, "include_libraries", false);
                    List<ModContainer> mods = new ArrayList<>(FabricLoader.getInstance().getAllMods());
                    mods.sort(Comparator.comparing(m -> m.getMetadata().getId()));
                    StringBuilder sb = new StringBuilder();
                    int n = 0;
                    for (ModContainer m : mods) {
                        ModMetadata md = m.getMetadata();
                        boolean lib = m.getContainingMod().isPresent() || md.getType().equals("builtin") || md.getId().startsWith("fabric-")
                                || md.containsCustomValue("fabric-loom:generated");
                        if (lib && !libs) continue;
                        String line = md.getId() + " | " + md.getName() + " | " + md.getVersion().getFriendlyString();
                        if (!f.isEmpty() && !(line + " " + md.getDescription()).toLowerCase(Locale.ROOT).contains(f)) continue;
                        sb.append(line).append('\n');
                        n++;
                    }
                    return ToolResult.ok(n + " mods:\n" + sb.toString().trim());
                }).build());

        out.add(Tool.builder("mod_info", Category.MODS, "Details of one mod: description, authors, links, dependencies, its config files and client commands/keybinds hints.")
                .param("id", "string", "Mod id from list_mods", true)
                .sync((ctx, a) -> {
                    String id = Args.reqStr(a, "id");
                    var c = FabricLoader.getInstance().getModContainer(id);
                    if (c.isEmpty()) return ToolResult.error("mod '" + id + "' is not installed");
                    ModMetadata md = c.get().getMetadata();
                    StringBuilder sb = new StringBuilder();
                    sb.append(md.getName()).append(" (").append(md.getId()).append(") ").append(md.getVersion().getFriendlyString()).append('\n');
                    sb.append("Description: ").append(md.getDescription()).append('\n');
                    sb.append("Authors: ");
                    md.getAuthors().forEach(p -> sb.append(p.getName()).append(", "));
                    sb.append('\n');
                    md.getContact().get("homepage").ifPresent(h -> sb.append("Homepage: ").append(h).append('\n'));
                    md.getContact().get("sources").ifPresent(h -> sb.append("Sources: ").append(h).append('\n'));
                    sb.append("Depends: ");
                    md.getDependencies().forEach(d -> sb.append(d.getModId()).append(' '));
                    sb.append('\n');
                    List<String> files = configFiles(id);
                    sb.append("Config files: ").append(files.isEmpty() ? "none found" : String.join(", ", files)).append('\n');
                    StringBuilder keys = new StringBuilder();
                    for (var k : Minecraft.getInstance().options.keyMappings) {
                        if (k.getName().toLowerCase(Locale.ROOT).contains(id.toLowerCase(Locale.ROOT).replace("-", "")) ||
                                k.getCategory().id().getNamespace().equals(id)) keys.append(k.getName()).append(' ');
                    }
                    if (!keys.isEmpty()) sb.append("Keybinds: ").append(keys).append('\n');
                    return ToolResult.ok(sb.toString().trim());
                }).build());

        out.add(Tool.builder("list_config_files", Category.MODS, "List files in the game's config folder (mod settings).")
                .param("filter", "string", "Optional text the path must contain", false)
                .sync((ctx, a) -> {
                    String f = Args.str(a, "filter", "").toLowerCase(Locale.ROOT);
                    Path root = FabricLoader.getInstance().getConfigDir();
                    List<String> list = new ArrayList<>();
                    try (Stream<Path> s = Files.walk(root, 4)) {
                        s.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                                .filter(p -> f.isEmpty() || p.toLowerCase(Locale.ROOT).contains(f))
                                .filter(p -> !hidden(p)).sorted().limit(300).forEach(list::add);
                    }
                    return ToolResult.ok(list.isEmpty() ? "no files" : String.join("\n", list));
                }).build());

        out.add(Tool.builder("read_config_file", Category.MODS, "Read a text file from the config folder (max 64 KB). Files that may contain credentials are refused.")
                .param("path", "string", "Path relative to the config folder, from list_config_files", true)
                .sync((ctx, a) -> {
                    Path root = FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
                    String rel = Args.reqStr(a, "path").replace('\\', '/');
                    Path p = root.resolve(rel).toAbsolutePath().normalize();
                    if (!p.startsWith(root)) return ToolResult.error("path outside the config folder");
                    if (hidden(root.relativize(p).toString().replace('\\', '/'))) return ToolResult.error("this file is private");
                    if (!Files.isRegularFile(p)) return ToolResult.error("no such file");
                    if (Files.size(p) > MAX_FILE_BYTES) return ToolResult.error("file too large (" + Files.size(p) + " bytes)");
                    return ToolResult.ok(Files.readString(p, StandardCharsets.UTF_8));
                }).build());

        out.add(Tool.builder("baritone", Category.BARITONE,
                        "Run a Baritone command (no prefix) for travel and automation: 'goto 100 64 -20' (stand at that spot), 'goto diamond_ore' (walk to the nearest one), "
                                + "'follow player Alex', 'explore', 'farm', 'come', 'thisway 100', 'stop'. For gathering blocks prefer the mine tool (it counts and stops). "
                                + "With wait=true the call returns when Baritone is idle again, with Baritone's own messages.")
                .param("command", "string", "Baritone command without '#'", true)
                .param("wait", "boolean", "Wait until Baritone finishes (default true)", false)
                .param("timeout_seconds", "number", "Max wait (default 180)", false)
                .available(ctx -> BaritoneBridge.available())
                .summary(a -> "baritone " + Args.str(a, "command", ""))
                .deny((ctx, a) -> {
                    // "goto x y z" means "stand exactly there": aimed at a solid block, Baritone digs it out first.
                    String[] parts = Args.str(a, "command", "").trim().replaceFirst("^#", "").split("\\s+");
                    if (parts.length == 4 && parts[0].equalsIgnoreCase("goto")) {
                        try {
                            var pos = new net.minecraft.core.BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
                            var level = Minecraft.getInstance().level;
                            if (level != null && level.isLoaded(pos) && !level.getBlockState(pos).canBeReplaced() && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                                return "goto x y z makes you STAND at that block, but " + pos.toShortString() + " is solid ("
                                        + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath()
                                        + ") and Baritone would dig it out. To get next to it use 'goto " + parts[1] + " " + (pos.getY() + 1) + " " + parts[3]
                                        + "' (on top) or a free spot beside it; to collect it use the mine tool.";
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    return null;
                })
                .async((ctx, a) -> {
                    String cmd = Args.reqStr(a, "command").trim();
                    if (cmd.startsWith("#")) cmd = cmd.substring(1);
                    long started = System.currentTimeMillis();
                    boolean ok;
                    try {
                        ok = BaritoneBridge.execute(cmd);
                    } catch (Throwable t) {
                        return CompletableFuture.completedFuture(ToolResult.error("Baritone failed: " + rootMessage(t)));
                    }
                    if (!ok) return CompletableFuture.completedFuture(ToolResult.error("Baritone did not accept '" + cmd + "'"));
                    String c = cmd;
                    if (!Args.bool(a, "wait", true) || cmd.startsWith("stop") || cmd.startsWith("cancel")) {
                        return ChatTools.collectFeedback(started, 500)
                                .thenApply(fb -> ToolResult.ok("baritone: " + c + (Args.bool(a, "wait", true) ? "" : " (running in the background)") + Mining.baritoneMessages(started, 4)));
                    }
                    int timeout = (int) (Args.num(a, "timeout_seconds", 180) * 20);
                    return Controller.run(Mining.baritoneWatch("baritone " + c, null), timeout).thenApply(r -> {
                        var p = Minecraft.getInstance().player;
                        String pos = p == null ? "" : String.format(Locale.ROOT, " | position %.1f %.1f %.1f", p.getX(), p.getY(), p.getZ());
                        String text = "baritone '" + c + "': " + r + pos + Mining.baritoneMessages(started, 4);
                        return Script.failed(r) ? ToolResult.error(text) : ToolResult.ok(text);
                    });
                }).build());

        out.add(Tool.builder("read_client_log", Category.CLIENT_LOG, "Read the last lines of the client log (logs/latest.log): errors, mod messages, connection info.")
                .param("lines", "integer", "Default 60, max per config", false)
                .param("filter", "string", "Optional case-insensitive regex, e.g. 'ERROR|WARN' or a mod name", false)
                .sync((ctx, a) -> {
                    int n = Math.max(1, Math.min(ctx.config.context.clientLogMaxLines, Args.integer(a, "lines", 60)));
                    List<String> lines = LogTail.tail(n, Args.str(a, "filter", ""));
                    String text = String.join("\n", lines);
                    // Never hand out anything that looks like a credential.
                    text = text.replaceAll("(?i)(access[_-]?token|api[_-]?key|authorization|bearer|session)([\"'=: ]+)\\S+", "$1$2<redacted>");
                    return ToolResult.ok(text.isEmpty() ? "(no matching lines)" : text);
                }).build());
    }

    static boolean hidden(String relPath) {
        String lower = relPath.toLowerCase(Locale.ROOT);
        if (lower.startsWith(ConfigManager.dir().getFileName().toString() + "/config.json")) return true;
        return SECRET_FILE.matcher(lower).matches();
    }

    static List<String> configFiles(String modId) {
        Path root = FabricLoader.getInstance().getConfigDir();
        String needle = modId.toLowerCase(Locale.ROOT).replace("-", "");
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root, 3)) {
            s.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> p.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").contains(needle.replace("_", "")))
                    .filter(p -> !hidden(p)).limit(30).forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
