package dev.silverx.llmcraft.game;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Melee fighting: picks the best weapon, closes in, hits only with a full attack charge, backs off from
 * creepers after hitting them, and retreats (returns) when health drops to the limit.
 */
public final class Combat {
    private Combat() {}

    /** Target filter from "hostiles", an entity type ("zombie"), "player:<name>" or an entity id. */
    public static Predicate<LivingEntity> targets(String query, int entityId) {
        if (entityId >= 0) return e -> e.getId() == entityId;
        String q = query == null ? "hostiles" : query.trim().toLowerCase(Locale.ROOT).replaceFirst("^minecraft:", "");
        if (q.isEmpty() || q.equals("hostiles") || q.equals("monsters") || q.equals("mobs")) {
            return e -> e instanceof net.minecraft.world.entity.monster.Enemy && !(e instanceof net.minecraft.world.entity.monster.EnderMan)
                    && !(e instanceof net.minecraft.world.entity.monster.zombie.ZombifiedPiglin);
        }
        if (q.startsWith("player:")) {
            String name = q.substring(7).trim();
            return e -> e instanceof Player pl && pl.getGameProfile().name().equalsIgnoreCase(name);
        }
        String type = q.endsWith("s") && BuiltInRegistries.ENTITY_TYPE.getOptional(net.minecraft.resources.Identifier.tryParse("minecraft:" + q)).isEmpty()
                ? q.substring(0, q.length() - 1) : q;
        return e -> !(e instanceof Player) && BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals(type);
    }

    /** Equips the strongest melee weapon (sword or axe by attack damage). Returns its name. */
    public static String equipWeapon(Minecraft mc) {
        var inv = mc.player.getInventory();
        int best = -1;
        double bestDmg = 1.0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            String id = Inv.id(s);
            if (!id.endsWith("_sword") && !id.endsWith("_axe") && !id.equals("trident") && !id.equals("mace")) continue;
            double dmg = damage(s) + (id.endsWith("_sword") ? 0.5 : 0); // swords swing faster
            if (dmg > bestDmg) {
                bestDmg = dmg;
                best = i;
            }
        }
        if (best >= 0) Inv.toHand(best);
        return Inv.id(mc.player.getMainHandItem());
    }

    private static double damage(ItemStack s) {
        var mods = s.get(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS);
        if (mods == null) return 1;
        double d = 1;
        for (var e : mods.modifiers()) {
            if (e.attribute().is(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) d += e.modifier().amount();
        }
        return d;
    }

    public static final class FightTask implements Controller.Task {
        private final Predicate<LivingEntity> filter;
        private final int count;
        private final float retreatHealth;
        private final double radius;
        private LivingEntity target;
        private final List<String> killed = new ArrayList<>();
        private int backOff;
        private int noHitTicks;
        private double bestDist = Double.MAX_VALUE;
        private String weapon;
        private Vec3 lastPos;
        private int stuck;

        public FightTask(Predicate<LivingEntity> filter, int count, float retreatHealth, double radius) {
            this.filter = filter;
            this.count = count;
            this.retreatHealth = retreatHealth;
            this.radius = radius;
        }

        @Override
        public boolean combat() {
            return true;
        }

        @Override
        public String tick(Minecraft mc) {
            var p = mc.player;
            if (weapon == null) weapon = equipWeapon(mc);
            if (p.getHealth() <= retreatHealth) {
                return Script.FAILED + String.format(Locale.ROOT, "health %.0f - stopped fighting to survive%s. Run (fight mode=flee), eat, then decide.",
                        p.getHealth(), result());
            }
            if (target != null && (!target.isAlive() || target.isRemoved())) {
                if (target.isDeadOrDying() || target.getHealth() <= 0) killed.add(Inv.entityName(target));
                target = null;
                bestDist = Double.MAX_VALUE;
                noHitTicks = 0;
            }
            if (killed.size() >= count) return "won" + result();
            if (target == null) {
                target = pick(mc);
                if (target == null) return killed.isEmpty() ? Script.FAILED + "no matching target within " + (int) radius + " blocks" : "no more targets" + result();
            }
            double dist = p.distanceTo(target);
            if (dist > radius * 1.5) {
                String lost = Inv.entityName(target);
                target = null;
                return killed.isEmpty() ? Script.FAILED + lost + " got away" : "target got away" + result();
            }
            Vec3 aim = target.getBoundingBox().getCenter();
            Controller.lookAt(aim);
            Inv.faceInstantly(aim);
            boolean creeper = target instanceof Creeper;
            if (backOff > 0) {
                backOff--;
                Controller.hold(mc.options.keyDown, 2);
                return null;
            }
            double reach = p.entityInteractionRange();
            if (dist > reach - 0.3) {
                double dx = target.getX() - p.getX(), dz = target.getZ() - p.getZ();
                double len = Math.max(1e-4, Math.sqrt(dx * dx + dz * dz));
                String danger = Danger.aheadOf(mc, dx / len, dz / len);
                if (danger != null) return Script.FAILED + "cannot reach " + Inv.entityName(target) + ": " + danger + result();
                Controller.hold(mc.options.keyUp, 2);
                if (dist > 4) Controller.hold(mc.options.keySprint, 2);
                if (p.horizontalCollision && p.onGround() || p.isInWater()) Controller.hold(mc.options.keyJump, 2);
                if (lastPos != null && lastPos.distanceToSqr(p.position()) < 0.001) {
                    if (++stuck > 30) return Script.FAILED + "stuck while chasing " + Inv.entityName(target) + result();
                } else stuck = 0;
                lastPos = p.position();
            }
            if (dist < bestDist - 0.5) {
                bestDist = dist;
                noHitTicks = 0;
            } else if (++noHitTicks > 20 * 8) {
                String gone = Inv.entityName(target);
                target = null;
                return Script.FAILED + "could not get at " + gone + " for 8 s (unreachable?)" + result();
            }
            if (dist <= reach && p.getAttackStrengthScale(0.5f) >= 0.95f && p.hasLineOfSight(target)) {
                mc.gameMode.attack(p, target);
                p.swing(InteractionHand.MAIN_HAND);
                noHitTicks = 0;
                if (creeper) backOff = 12; // hit and step back before it blows
            }
            return null;
        }

        private LivingEntity pick(Minecraft mc) {
            var p = mc.player;
            List<LivingEntity> list = new ArrayList<>();
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == p || !(e instanceof LivingEntity le) || !le.isAlive()) continue;
                if (le.distanceTo(p) <= radius && filter.test(le)) list.add(le);
            }
            list.sort(Comparator.comparingDouble(e -> e.distanceTo(p)));
            return list.isEmpty() ? null : list.getFirst();
        }

        private String result() {
            return killed.isEmpty() ? "" : " (killed " + killed.size() + ": " + String.join(", ", killed) + ")";
        }

        @Override
        public void onStop() {
            var o = Minecraft.getInstance().options;
            Controller.release(o.keyUp);
            Controller.release(o.keyDown);
            Controller.release(o.keySprint);
            Controller.release(o.keyJump);
        }

        @Override
        public String describe() {
            return "fighting" + (target != null ? " " + Inv.entityName(target) : "");
        }
    }
}
