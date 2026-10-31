package dev.silverx.llmcraft.tool.impl;

import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.game.Controller;
import dev.silverx.llmcraft.game.Snapshot;
import dev.silverx.llmcraft.tool.Args;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolResult;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class InfoTools {
    private InfoTools() {}

    public static void register(List<Tool> out) {
        out.add(Tool.builder("player_status", Category.PLAYER_READ, "Detailed status of the player: health, food, position, rotation, effects, held items.")
                .sync((ctx, a) -> {
                    Minecraft mc = Minecraft.getInstance();
                    var p = mc.player;
                    if (p == null) return ToolResult.error("not in a world");
                    StringBuilder sb = new StringBuilder(Snapshot.playerLine(mc)).append('\n');
                    sb.append("Main hand: ").append(Snapshot.stack(p.getMainHandItem())).append(" | offhand: ").append(Snapshot.stack(p.getOffhandItem())).append('\n');
                    sb.append(String.format(Locale.ROOT, "Saturation %.1f | air %d/%d | on ground %b | sprinting %b | sneaking %b | flying %b%n",
                            p.getFoodData().getSaturationLevel(), p.getAirSupply(), p.getMaxAirSupply(), p.onGround(), p.isSprinting(),
                            p.isShiftKeyDown(), p.getAbilities().flying));
                    var effects = p.getActiveEffects();
                    if (!effects.isEmpty()) {
                        sb.append("Effects: ");
                        effects.forEach(e -> sb.append(e.getEffect().unwrapKey().map(k -> k.identifier().getPath()).orElse("?"))
                                .append(' ').append(e.getAmplifier() + 1).append(" (").append(e.getDuration() / 20).append("s) "));
                        sb.append('\n');
                    }
                    var active = Controller.describeActive();
                    if (!active.isEmpty()) sb.append("AI actions running: ").append(String.join(", ", active)).append('\n');
                    return ToolResult.ok(sb.toString().trim());
                }).build());

        out.add(Tool.builder("inventory", Category.PLAYER_READ, "List every non-empty slot of the player's inventory with slot numbers.")
                .sync((ctx, a) -> {
                    var p = Minecraft.getInstance().player;
                    return p == null ? ToolResult.error("not in a world") : ToolResult.ok(Snapshot.inventoryDetailed(p));
                }).build());

        out.add(Tool.builder("world_info", Category.WORLD_READ, "Time, weather, dimension, biome, difficulty, what the crosshair targets, online players and scoreboard.")
                .sync((ctx, a) -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level == null) return ToolResult.error("not in a world");
                    String sb = Snapshot.worldLine(mc) + "\nLooking at: " + Snapshot.crosshair(mc)
                            + "\nOnline: " + Snapshot.playerList(mc, 100)
                            + "\nScoreboard: " + Snapshot.sidebar(mc);
                    return ToolResult.ok(sb);
                }).build());

        out.add(Tool.builder("get_block", Category.WORLD_READ, "Get the block (with block state properties) at a position.")
                .param("x", "integer", "X", true).param("y", "integer", "Y", true).param("z", "integer", "Z", true)
                .sync((ctx, a) -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level == null) return ToolResult.error("not in a world");
                    BlockPos pos = new BlockPos(Args.integer(a, "x", 0), Args.integer(a, "y", 0), Args.integer(a, "z", 0));
                    if (!mc.level.isLoaded(pos)) return ToolResult.error("chunk not loaded");
                    BlockState st = mc.level.getBlockState(pos);
                    return ToolResult.ok(st.toString().replace("Block{", "").replace("}", "") + " at " + pos.toShortString()
                            + " | light " + mc.level.getMaxLocalRawBrightness(pos));
                }).build());

        out.add(Tool.builder("find_blocks", Category.WORLD_READ,
                        "Find the nearest blocks whose id contains the given text (e.g. 'chest', 'bed', 'water') within a radius around the player. "
                                + "Only for knowing where something is - to collect blocks call mine directly, it finds them itself (also far away).")
                .param("block", "string", "Part of the block id, e.g. 'iron_ore' or 'minecraft:oak_log'", true)
                .param("radius", "integer", "Search radius (default 16, capped by config)", false)
                .param("max_results", "integer", "Default 10", false)
                .sync((ctx, a) -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level == null) return ToolResult.error("not in a world");
                    String q = Args.reqStr(a, "block").toLowerCase(Locale.ROOT);
                    int r = Math.max(1, Math.min(ctx.config.context.maxScanRadius, Args.integer(a, "radius", 16)));
                    int max = Math.max(1, Math.min(50, Args.integer(a, "max_results", 10)));
                    BlockPos c = mc.player.blockPosition();
                    record Hit(BlockPos pos, String id, double d) {}
                    List<Hit> hits = new ArrayList<>();
                    BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
                    int minY = Math.max(mc.level.getMinY(), c.getY() - r), maxY = Math.min(mc.level.getMaxY(), c.getY() + r);
                    for (int x = c.getX() - r; x <= c.getX() + r; x++)
                        for (int z = c.getZ() - r; z <= c.getZ() + r; z++)
                            for (int y = minY; y <= maxY; y++) {
                                m.set(x, y, z);
                                BlockState st = mc.level.getBlockState(m);
                                if (st.isAir()) continue;
                                String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                                if (id.contains(q)) hits.add(new Hit(m.immutable(), id, Math.sqrt(m.distSqr(c))));
                            }
                    hits.sort(Comparator.comparingDouble(Hit::d));
                    if (hits.isEmpty()) {
                        String hint = dev.silverx.llmcraft.game.BaritoneBridge.available()
                                ? " (only loaded blocks this close were checked - that does NOT mean there is none. To get it, call mine with it: Baritone searches its world cache and explores until it finds it)"
                                : "";
                        return ToolResult.ok("no '" + q + "' within " + r + " blocks" + hint);
                    }
                    StringBuilder sb = new StringBuilder(hits.size() + " found (nearest first):\n");
                    for (int i = 0; i < Math.min(max, hits.size()); i++) {
                        Hit h = hits.get(i);
                        sb.append(String.format(Locale.ROOT, "%s at %d %d %d (%.1fm)%n", h.id, h.pos.getX(), h.pos.getY(), h.pos.getZ(), h.d));
                    }
                    return ToolResult.ok(sb.toString().trim());
                }).build());

        out.add(Tool.builder("nearby_entities", Category.WORLD_READ, "List entities (mobs, players, items, vehicles) near the player, nearest first. Each has an #id usable by other tools.")
                .param("radius", "number", "Default 32", false)
                .param("filter", "string", "Optional text the description must contain, e.g. 'zombie' or a player name", false)
                .param("max_results", "integer", "Default 20", false)
                .sync((ctx, a) -> {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level == null) return ToolResult.error("not in a world");
                    String s = Snapshot.nearbyEntities(mc, Math.min(128, Args.num(a, "radius", 32)), Math.min(60, Args.integer(a, "max_results", 20)), Args.str(a, "filter", null));
                    return ToolResult.ok(s.isEmpty() ? "no entities found" : s.replace("; ", "\n"));
                }).build());

        out.add(Tool.builder("wait", Category.WORLD_READ, "Wait some seconds (e.g. for something to happen), then continue.")
                .param("seconds", "number", "0.05 - 60", true)
                .async((ctx, a) -> {
                    int ticks = (int) Math.max(1, Math.min(1200, Math.round(Args.num(a, "seconds", 1) * 20)));
                    return Controller.run(Controller.waitTicks(ticks), ticks + 5).thenApply(ToolResult::ok);
                }).build());
    }
}
