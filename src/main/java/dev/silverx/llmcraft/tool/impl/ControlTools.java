package dev.silverx.llmcraft.tool.impl;

import dev.silverx.llmcraft.config.Category;
import dev.silverx.llmcraft.game.Controller;
import dev.silverx.llmcraft.game.Snapshot;
import dev.silverx.llmcraft.tool.Args;
import dev.silverx.llmcraft.tool.Tool;
import dev.silverx.llmcraft.tool.ToolResult;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class ControlTools {
    private ControlTools() {}

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static CompletableFuture<ToolResult> err(String msg) {
        return CompletableFuture.completedFuture(ToolResult.error(msg));
    }

    public static void register(List<Tool> out) {
        // ---------------- movement ----------------
        out.add(Tool.builder("look", Category.MOVEMENT,
                        "Turn the camera to an absolute yaw/pitch. Yaw: 0=south(+Z), 90=west(-X), 180/-180=north(-Z), -90=east(+X). Pitch: -90 up, 0 level, 90 down.")
                .param("yaw", "number", "Yaw in degrees", true).param("pitch", "number", "Pitch in degrees (default 0)", false)
                .async((ctx, a) -> {
                    if (mc().player == null) return err("not in a world");
                    Controller.look((float) Args.reqNum(a, "yaw"), (float) Args.num(a, "pitch", 0));
                    return Controller.run(Controller.settleLook(), 100).thenApply(ToolResult::ok);
                }).build());

        out.add(Tool.builder("look_at", Category.MOVEMENT, "Turn the camera towards a position or an entity (#id from nearby_entities).")
                .param("x", "number", "X (block coordinates are centered automatically)", false)
                .param("y", "number", "Y", false).param("z", "number", "Z", false)
                .param("entity_id", "integer", "Look at this entity instead", false)
                .async((ctx, a) -> {
                    if (mc().player == null) return err("not in a world");
                    Vec3 target;
                    if (Args.has(a, "entity_id")) {
                        Entity e = mc().level.getEntity(Args.integer(a, "entity_id", -1));
                        if (e == null) return err("no entity with that id nearby");
                        target = e.getEyePosition();
                    } else {
                        target = blockCenter(Args.reqNum(a, "x"), Args.reqNum(a, "y"), Args.reqNum(a, "z"));
                    }
                    Controller.lookAt(target);
                    return Controller.run(Controller.settleLook(), 100).thenApply(r -> ToolResult.ok(r + " | now targeting: " + Snapshot.crosshair(mc())));
                }).build());

        out.add(Tool.builder("move", Category.MOVEMENT, "Hold movement keys for some time (relative to where the player looks).")
                .arrayParam("directions", "string", "Any of: forward, back, left, right, jump, sneak, sprint", true)
                .param("seconds", "number", "How long (default 1, max per config)", false)
                .async((ctx, a) -> {
                    if (mc().player == null) return err("not in a world");
                    var o = mc().options;
                    List<KeyMapping> keys = new ArrayList<>();
                    var arr = a.get("directions");
                    List<String> dirs = new ArrayList<>();
                    if (arr != null && arr.isJsonArray()) arr.getAsJsonArray().forEach(e -> dirs.add(e.getAsString()));
                    else dirs.add(Args.str(a, "directions", "forward"));
                    for (String d : dirs) {
                        switch (d.toLowerCase(Locale.ROOT).trim()) {
                            case "forward", "forwards", "w" -> keys.add(o.keyUp);
                            case "back", "backward", "backwards", "s" -> keys.add(o.keyDown);
                            case "left", "a" -> keys.add(o.keyLeft);
                            case "right", "d" -> keys.add(o.keyRight);
                            case "jump", "up", "space" -> keys.add(o.keyJump);
                            case "sneak", "crouch", "shift", "down" -> keys.add(o.keyShift);
                            case "sprint", "run" -> keys.add(o.keySprint);
                            default -> {
                                return err("unknown direction '" + d + "'");
                            }
                        }
                    }
                    int ticks = (int) Math.max(1, Math.round(Args.num(a, "seconds", 1) * 20));
                    Vec3 start = mc().player.position();
                    return Controller.run(Controller.holdFor(keys, ticks), ticks + 10)
                            .thenApply(r -> ToolResult.ok(String.format(Locale.ROOT, "%s; moved %.1f blocks, now at %.1f %.1f %.1f", r,
                                    mc().player.position().distanceTo(start), mc().player.getX(), mc().player.getY(), mc().player.getZ())));
                }).build());

        out.add(Tool.builder("walk_to", Category.MOVEMENT,
                        "Walk in a straight line to x/z (simple: jumps 1-block steps, no real pathfinding - prefer the baritone tool if available for long or complex paths).")
                .param("x", "number", "Target X", true).param("z", "number", "Target Z", true)
                .param("sprint", "boolean", "Sprint (default true)", false)
                .param("tolerance", "number", "Stop within this distance (default 0.8)", false)
                .param("timeout_seconds", "number", "Default 60", false)
                .async((ctx, a) -> {
                    if (mc().player == null) return err("not in a world");
                    Vec3 target = new Vec3(Args.reqNum(a, "x"), mc().player.getY(), Args.reqNum(a, "z"));
                    int timeout = (int) (Args.num(a, "timeout_seconds", 60) * 20);
                    return Controller.run(Controller.walkTo(target, Args.bool(a, "sprint", true), Math.max(0.3, Args.num(a, "tolerance", 0.8))), timeout)
                            .thenApply(ToolResult::ok);
                }).build());

        out.add(Tool.builder("jump", Category.MOVEMENT, "Jump once.")
                .sync((ctx, a) -> {
                    if (mc().player == null) return ToolResult.error("not in a world");
                    Controller.hold(mc().options.keyJump, 3);
                    return ToolResult.ok("jumped");
                }).build());

        out.add(Tool.builder("stop_actions", Category.MOVEMENT, "Immediately stop every running AI movement/action and release all keys.")
                .sync((ctx, a) -> {
                    Controller.stopAll("requested by AI");
                    return ToolResult.ok("stopped");
                }).build());

        // ---------------- interaction ----------------
        out.add(Tool.builder("attack", Category.INTERACTION,
                        "Attack (left click). With entity_id: turn to that entity and hit it if in reach. Without: left click whatever the crosshair targets.")
                .param("entity_id", "integer", "Entity #id from nearby_entities", false)
                .summary(a -> Args.has(a, "entity_id") ? "attack entity #" + Args.integer(a, "entity_id", -1) : "left click")
                .async((ctx, a) -> {
                    var p = mc().player;
                    if (p == null) return err("not in a world");
                    if (!Args.has(a, "entity_id")) {
                        Controller.tap(mc().options.keyAttack);
                        return CompletableFuture.completedFuture(ToolResult.ok("left clicked on " + Snapshot.crosshair(mc())));
                    }
                    Entity e = mc().level.getEntity(Args.integer(a, "entity_id", -1));
                    if (e == null) return err("no entity with that id nearby");
                    Controller.lookAt(e.getBoundingBox().getCenter());
                    return Controller.run(Controller.settleLook(), 60).thenApply(r -> {
                        if (p.distanceTo(e) > p.entityInteractionRange() + 1) return ToolResult.error(String.format(Locale.ROOT, "too far (%.1fm), move closer first", p.distanceTo(e)));
                        mc().gameMode.attack(p, e);
                        p.swing(InteractionHand.MAIN_HAND);
                        return ToolResult.ok("hit " + Snapshot.entity(mc(), e));
                    });
                }).build());

        out.add(Tool.builder("use_item", Category.INTERACTION,
                        "Use the held item / right click (eat, drink, open a door or chest that the crosshair targets, throw, shoot). hold_seconds keeps right click held (eating ~1.6s, bow ~1s).")
                .param("hold_seconds", "number", "Hold right click this long (default: single click)", false)
                .summary(a -> "right click" + (Args.has(a, "hold_seconds") ? " for " + Args.num(a, "hold_seconds", 0) + "s" : ""))
                .async((ctx, a) -> {
                    if (mc().player == null) return err("not in a world");
                    double hold = Args.num(a, "hold_seconds", 0);
                    String target = Snapshot.crosshair(mc());
                    if (hold <= 0) {
                        Controller.tap(mc().options.keyUse);
                        return CompletableFuture.completedFuture(ToolResult.ok("right clicked (" + Snapshot.stack(mc().player.getMainHandItem()) + " on " + target + ")"));
                    }
                    int ticks = (int) Math.round(hold * 20);
                    return Controller.run(Controller.holdFor(List.of(mc().options.keyUse), ticks), ticks + 10)
                            .thenApply(r -> ToolResult.ok("held right click " + hold + "s on " + target));
                }).build());

        out.add(Tool.builder("interact_entity", Category.INTERACTION, "Right click an entity (trade with villager, mount, feed, name...).")
                .param("entity_id", "integer", "Entity #id", true)
                .async((ctx, a) -> {
                    var p = mc().player;
                    if (p == null) return err("not in a world");
                    Entity e = mc().level.getEntity(Args.integer(a, "entity_id", -1));
                    if (e == null) return err("no entity with that id nearby");
                    Controller.lookAt(e.getBoundingBox().getCenter());
                    return Controller.run(Controller.settleLook(), 60).thenApply(r -> {
                        if (p.distanceTo(e) > p.entityInteractionRange() + 1) return ToolResult.error("too far, move closer first");
                        var res = mc().gameMode.interact(p, e, InteractionHand.MAIN_HAND);
                        p.swing(InteractionHand.MAIN_HAND);
                        return ToolResult.ok("interacted with " + Snapshot.entity(mc(), e) + ": " + res);
                    });
                }).build());

        out.add(Tool.builder("mine_block", Category.INTERACTION, "Break the block at a position (must be within reach, ~4.5 blocks). Holds left click until it breaks.")
                .param("x", "integer", "X", true).param("y", "integer", "Y", true).param("z", "integer", "Z", true)
                .param("timeout_seconds", "number", "Default 30", false)
                .async((ctx, a) -> {
                    if (mc().player == null) return err("not in a world");
                    BlockPos pos = new BlockPos(Args.integer(a, "x", 0), Args.integer(a, "y", 0), Args.integer(a, "z", 0));
                    if (mc().level.getBlockState(pos).isAir()) return err("there is no block at " + pos.toShortString());
                    return Controller.run(Controller.mine(pos), (int) (Args.num(a, "timeout_seconds", 30) * 20)).thenApply(ToolResult::ok);
                }).build());

        out.add(Tool.builder("place_block", Category.INTERACTION,
                        "Place the held block at a position (needs an adjacent solid block and must be in reach). Select the block with select_slot first.")
                .param("x", "integer", "X", true).param("y", "integer", "Y", true).param("z", "integer", "Z", true)
                .async((ctx, a) -> {
                    var p = mc().player;
                    if (p == null) return err("not in a world");
                    BlockPos pos = new BlockPos(Args.integer(a, "x", 0), Args.integer(a, "y", 0), Args.integer(a, "z", 0));
                    var level = mc().level;
                    if (!level.getBlockState(pos).canBeReplaced()) return err("position is occupied by " + level.getBlockState(pos).getBlock().getName().getString());
                    BlockHitResult hit = null;
                    for (Direction d : Direction.values()) {
                        BlockPos n = pos.relative(d);
                        if (level.getBlockState(n).canBeReplaced()) continue;
                        Vec3 face = Vec3.atCenterOf(n).add(Vec3.atLowerCornerOf(d.getOpposite().getUnitVec3i()).scale(0.5));
                        hit = new BlockHitResult(face, d.getOpposite(), n, false);
                        break;
                    }
                    if (hit == null) return err("no adjacent block to place against");
                    if (p.getEyePosition().distanceTo(hit.getLocation()) > p.blockInteractionRange()) return err("out of reach, move closer");
                    BlockHitResult finalHit = hit;
                    Controller.lookAt(hit.getLocation());
                    return Controller.run(Controller.settleLook(), 60).thenApply(r -> {
                        var res = mc().gameMode.useItemOn(p, InteractionHand.MAIN_HAND, finalHit);
                        p.swing(InteractionHand.MAIN_HAND);
                        String now = level.getBlockState(pos).getBlock().getName().getString();
                        return ToolResult.ok("place result " + res + "; block at " + pos.toShortString() + " is now " + now);
                    });
                }).build());

        // ---------------- inventory ----------------
        out.add(Tool.builder("select_slot", Category.INVENTORY, "Select a hotbar slot (0-8).")
                .param("slot", "integer", "0-8", true)
                .sync((ctx, a) -> {
                    var p = mc().player;
                    if (p == null) return ToolResult.error("not in a world");
                    int s = Args.integer(a, "slot", -1);
                    if (s < 0 || s > 8) return ToolResult.error("slot must be 0-8");
                    p.getInventory().setSelectedSlot(s);
                    return ToolResult.ok("selected slot " + s + ": " + Snapshot.stack(p.getInventory().getItem(s)));
                }).build());

        out.add(Tool.builder("drop_item", Category.INVENTORY, "Drop the held item (one, or the whole stack).")
                .param("whole_stack", "boolean", "Drop the full stack (default false)", false)
                .sync((ctx, a) -> {
                    var p = mc().player;
                    if (p == null) return ToolResult.error("not in a world");
                    String what = Snapshot.stack(p.getMainHandItem());
                    if (p.getMainHandItem().isEmpty()) return ToolResult.error("hand is empty");
                    boolean all = Args.bool(a, "whole_stack", false);
                    p.drop(all);
                    p.swing(InteractionHand.MAIN_HAND);
                    return ToolResult.ok("dropped " + (all ? what : "one of " + what));
                }).build());

        out.add(Tool.builder("swap_hands", Category.INVENTORY, "Swap main hand and offhand items.")
                .sync((ctx, a) -> {
                    if (mc().player == null) return ToolResult.error("not in a world");
                    Controller.tap(mc().options.keySwapOffhand);
                    return ToolResult.ok("swapped");
                }).build());

        out.add(Tool.builder("open_inventory", Category.INVENTORY, "Open the player's inventory screen (then use read_screen / click_slot).")
                .sync((ctx, a) -> {
                    if (mc().player == null) return ToolResult.error("not in a world");
                    mc().setScreen(new InventoryScreen(mc().player));
                    return ToolResult.ok("inventory opened");
                }).build());
    }

    static Vec3 blockCenter(double x, double y, double z) {
        boolean integral = x == Math.floor(x) && y == Math.floor(y) && z == Math.floor(z);
        return integral ? new Vec3(x + 0.5, y + 0.5, z + 0.5) : new Vec3(x, y, z);
    }
}
