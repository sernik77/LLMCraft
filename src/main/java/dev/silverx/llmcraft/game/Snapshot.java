package dev.silverx.llmcraft.game;

import dev.silverx.llmcraft.config.LlmConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Compact text descriptions of the game state. Must be called on the client thread. */
public final class Snapshot {
    private Snapshot() {}

    public static String build(LlmConfig cfg, boolean playerRead, boolean worldRead, boolean screenRead) {
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        var level = mc.level;
        if (p == null || level == null) return "Not in a world (main menu).";
        StringBuilder sb = new StringBuilder();
        if (playerRead) {
            sb.append(playerLine(mc)).append('\n');
            sb.append("Holding: ").append(stack(p.getMainHandItem())).append(" | offhand: ").append(stack(p.getOffhandItem()))
                    .append(" | hotbar slot ").append(p.getInventory().getSelectedSlot()).append('\n');
            if (cfg.context.includeInventorySummary) sb.append("Inventory: ").append(inventorySummary(p, 25)).append('\n');
            sb.append(canCheat(mc) ? "Operator commands: allowed (cheats on)\n"
                    : "Operator commands: NOT allowed (cheats off / not op) - /give, /tp, /gamemode, /time etc. will fail; get things by playing\n");
            if (BaritoneBridge.available()) sb.append("Baritone: installed, ").append(BaritoneBridge.isBusy() ? "currently BUSY" : "idle").append('\n');
        }
        if (worldRead) {
            sb.append(worldLine(mc)).append('\n');
            sb.append("Looking at: ").append(crosshair(mc)).append('\n');
            if (cfg.context.includeNearbyEntities) {
                String ents = nearbyEntities(mc, cfg.context.entityRadius, cfg.context.maxEntities, null);
                sb.append("Nearby (").append(cfg.context.entityRadius).append("m): ").append(ents.isEmpty() ? "nothing" : ents).append('\n');
            }
            if (cfg.context.includePlayerList && !mc.isLocalServer()) sb.append("Online: ").append(playerList(mc, 30)).append('\n');
            String sidebar = sidebar(mc);
            if (!sidebar.isEmpty()) sb.append("Scoreboard: ").append(sidebar).append('\n');
        }
        if (screenRead && cfg.context.includeScreenInfo) sb.append("Open screen: ").append(screenName(mc.screen)).append('\n');
        return sb.toString().trim();
    }

    /** One short line appended to action results so the model always knows where things stand. */
    public static String statusLine(Minecraft mc) {
        var p = mc.player;
        if (p == null) return "not in a world";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "hp %.0f/%.0f food %d", p.getHealth(), p.getMaxHealth(), p.getFoodData().getFoodLevel()));
        if (p.getAirSupply() < p.getMaxAirSupply()) sb.append(" air ").append(Math.max(0, p.getAirSupply())).append('/').append(p.getMaxAirSupply());
        if (p.isInWater()) sb.append(" in water");
        if (p.isInLava()) sb.append(" IN LAVA");
        sb.append(String.format(Locale.ROOT, " | pos %.1f %.1f %.1f", p.getX(), p.getY(), p.getZ()));
        sb.append(" | holding ").append(stack(p.getMainHandItem()));
        if (BaritoneBridge.available()) sb.append(" | baritone ").append(BaritoneBridge.isBusy() ? "BUSY" : "idle");
        String threats = Danger.summary(mc);
        if (threats != null) sb.append(" | ").append(threats);
        String stuck = Stuck.trapped(mc);
        if (stuck != null) sb.append(" | ").append(stuck);
        if (mc.screen != null) sb.append(" | screen ").append(screenName(mc.screen));
        return sb.toString();
    }

    /** Whether the server lets the player use operator commands (/give, /tp, /time...). */
    public static boolean canCheat(Minecraft mc) {
        return mc.player != null && mc.player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    public static String playerLine(Minecraft mc) {
        var p = mc.player;
        String mode = mc.gameMode == null ? "?" : mc.gameMode.getPlayerMode().getName();
        return String.format(Locale.ROOT,
                "Player %s | %s | health %.1f/%.0f | food %d | armor %d | xp level %d | pos %.1f %.1f %.1f | yaw %.0f pitch %.0f (facing %s)%s%s%s",
                p.getGameProfile().name(), mode, p.getHealth(), p.getMaxHealth(), p.getFoodData().getFoodLevel(), p.getArmorValue(),
                p.experienceLevel, p.getX(), p.getY(), p.getZ(), net.minecraft.util.Mth.wrapDegrees(p.getYRot()), p.getXRot(),
                p.getDirection().getName(), p.isInWater() ? " | in water" : "", p.isOnFire() ? " | ON FIRE" : "",
                p.isSleeping() ? " | sleeping" : "");
    }

    public static String worldLine(Minecraft mc) {
        var level = mc.level;
        BlockPos pos = mc.player.blockPosition();
        long day = level.getDayTime();
        long tod = Math.floorMod(day, 24000L);
        int hours = (int) ((tod / 1000 + 6) % 24);
        int minutes = (int) (tod % 1000 * 60 / 1000);
        String biome = level.getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
        String weather = level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear";
        String where = mc.isLocalServer() ? "singleplayer" : "server " + (mc.getCurrentServer() == null ? "?" : mc.getCurrentServer().ip);
        return String.format(Locale.ROOT, "World: %s | dimension %s | biome %s | day %d %02d:%02d | %s | difficulty %s | light %d",
                where, level.dimension().identifier(), biome, day / 24000L, hours, minutes, weather,
                level.getDifficulty().getKey(), level.getMaxLocalRawBrightness(pos));
    }

    public static String crosshair(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) return "nothing (air)";
        if (hit instanceof BlockHitResult bhr) {
            BlockPos bp = bhr.getBlockPos();
            return "block " + BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(bp).getBlock()) + " at " + bp.getX() + " " + bp.getY() + " " + bp.getZ()
                    + " (face " + bhr.getDirection().getName() + String.format(Locale.ROOT, ", %.1fm)", mc.player.getEyePosition().distanceTo(bhr.getLocation()));
        }
        if (hit instanceof EntityHitResult ehr) return "entity " + entity(mc, ehr.getEntity());
        return hit.getType().name();
    }

    public static String entity(Minecraft mc, Entity e) {
        StringBuilder sb = new StringBuilder();
        sb.append(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath());
        if (e instanceof Player pl) sb.append(" \"").append(pl.getGameProfile().name()).append('"');
        else if (e.hasCustomName()) sb.append(" \"").append(e.getCustomName().getString()).append('"');
        if (e instanceof ItemEntity ie) sb.append(" (").append(stack(ie.getItem())).append(')');
        sb.append(" #").append(e.getId());
        if (e instanceof LivingEntity le) sb.append(String.format(Locale.ROOT, " hp %.0f", le.getHealth()));
        if (mc.player != null) {
            sb.append(String.format(Locale.ROOT, " %.1fm %s", mc.player.distanceTo(e), compass(mc.player.position(), e.position())));
            sb.append(String.format(Locale.ROOT, " @%.0f %.0f %.0f", e.getX(), e.getY(), e.getZ()));
        }
        return sb.toString();
    }

    public static String compass(net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return dirs[(int) Math.floorMod(Math.round(angle / 45.0), 8)];
    }

    public static String nearbyEntities(Minecraft mc, double radius, int max, String filter) {
        var p = mc.player;
        List<Entity> list = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || e.distanceTo(p) > radius) continue;
            if (filter != null && !filter.isBlank()) {
                String desc = entity(mc, e).toLowerCase(Locale.ROOT);
                if (!desc.contains(filter.toLowerCase(Locale.ROOT))) continue;
            }
            list.add(e);
        }
        list.sort(Comparator.comparingDouble(e -> e.distanceTo(p)));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, list.size()); i++) {
            if (i > 0) sb.append("; ");
            sb.append(entity(mc, list.get(i)));
        }
        if (list.size() > max) sb.append("; +").append(list.size() - max).append(" more");
        return sb.toString();
    }

    public static String stack(ItemStack s) {
        if (s == null || s.isEmpty()) return "empty";
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        StringBuilder sb = new StringBuilder();
        if (s.getCount() > 1) sb.append(s.getCount()).append("x ");
        sb.append(id.startsWith("minecraft:") ? id.substring(10) : id);
        String name = s.getHoverName().getString();
        if (s.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)) sb.append(" \"").append(name).append('"');
        if (s.isDamageableItem()) sb.append(" (").append(s.getMaxDamage() - s.getDamageValue()).append('/').append(s.getMaxDamage()).append(')');
        return sb.toString();
    }

    public static String inventorySummary(Player p, int maxKinds) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString().replace("minecraft:", "");
            counts.merge(id, s.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) return "empty";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (var e : counts.entrySet()) {
            if (n++ >= maxKinds) {
                sb.append(", +").append(counts.size() - maxKinds).append(" more kinds");
                break;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getValue()).append(' ').append(e.getKey());
        }
        return sb.toString();
    }

    public static String inventoryDetailed(Player p) {
        StringBuilder sb = new StringBuilder();
        var inv = p.getInventory();
        sb.append("Selected hotbar slot: ").append(inv.getSelectedSlot()).append(" (slots 0-8 are the hotbar, 9-35 main inventory, 36-39 armor feet..head, 40 offhand)\n");
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) sb.append(i).append(": ").append(stack(s)).append('\n');
        }
        return sb.toString().trim();
    }

    public static String playerList(Minecraft mc, int max) {
        if (mc.getConnection() == null) return "";
        List<String> names = new ArrayList<>();
        for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) names.add(info.getProfile().name());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        int total = names.size();
        if (total > max) names = names.subList(0, max);
        return total + " players: " + String.join(", ", names) + (total > max ? ", ..." : "");
    }

    public static String sidebar(Minecraft mc) {
        var sb = mc.level.getScoreboard();
        Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) return "";
        List<PlayerScoreEntry> entries = new ArrayList<>(sb.listPlayerScores(obj));
        entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());
        StringBuilder out = new StringBuilder(obj.getDisplayName().getString()).append(" — ");
        int n = 0;
        for (PlayerScoreEntry e : entries) {
            if (e.isHidden()) continue;
            if (n++ >= 15) break;
            String label = e.display() != null ? e.display().getString() : e.owner();
            out.append(label).append(' ').append(e.value()).append("; ");
        }
        return out.toString().trim();
    }

    public static String screenName(Screen s) {
        if (s == null) return "none (in game)";
        String title = s.getTitle().getString();
        String type = s.getClass().getSimpleName();
        if (s instanceof AbstractContainerScreen<?>) type += " (container)";
        return (title.isBlank() ? "" : "\"" + title + "\" ") + "[" + type + "]";
    }
}
