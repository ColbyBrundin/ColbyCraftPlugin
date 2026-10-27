package com.example.vanillakfc.listeners;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Skeletons become stationary snipers: while actively targeting a living
 * entity, movement speed is cut via a toggled {@link AttributeModifier}
 * (same hasModifier/addModifier/removeModifier-by-key pattern as
 * {@link CreeperBehaviorTask}'s freeze modifier, but driven by
 * {@link EntityTargetLivingEntityEvent} instead of a per-tick scan, since
 * that event already fires once per on/off/switch transition), their
 * detection range is extended to {@link #VIEW_RANGE}, and every fired arrow
 * leaves at {@link #SHOT_VELOCITY_MULTIPLIER} vanilla's speed (which carries
 * proportionally further too, for free, as a straight consequence of the
 * faster launch).
 *
 * <p>The shot rate itself is hard-locked to a fixed tick count between shots
 * (double vanilla's own ~3s on Easy/Normal, and 2.5x its ~2s on Hard - both
 * requested as exact counts rather than an approximation of vanilla's) via
 * {@link ThrottleGoal}, a competing AI goal rather than a post-hoc cancel on
 * {@link EntityShootBowEvent}: that event only fires once the bow's already
 * fully drawn, so cancelling it there can't undo the draw animation the
 * player already saw - it just makes shots randomly fizzle with nothing
 * coming out. Claiming the same {@code MOVE} flag vanilla's own
 * RangedBowAttackGoal uses instead blocks a new draw from ever starting
 * while active, and since it also checks {@code isHandRaised()}, it never
 * interrupts a draw already underway - every draw that starts always ends in
 * a fired arrow. Tracked by {@link Bukkit#getCurrentTick()} rather than
 * wall-clock time so the interval is an exact tick count, not something that
 * can drift a tick or two under server lag.
 *
 * <p>Freezing movement while targeting has a side effect: vanilla's own
 * target-acquisition goal forgets a target it hasn't been able to see for
 * long enough ({@code FORGOT_TARGET}), and a frozen skeleton has no way to
 * reposition to regain sight of it - e.g. a player's tamed pet lands a hit
 * (retargeting the skeleton onto the pet per vanilla's own retaliation
 * behavior), then steps out of view, and the skeleton just goes idle with no
 * target at all rather than falling back to the player. {@link
 * #onSkeletonTarget} closes that gap: whenever a skeleton's target would
 * become null, it re-targets whoever last damaged it (tracked via {@link
 * #onSkeletonDamaged}) if they're still visible, falling back to the
 * nearest visible player otherwise - so a skeleton with the player or their
 * pet in view is never left doing nothing. The re-target is deliberately
 * done via a fresh, independent {@code setTarget()} call one tick later
 * rather than rewriting the null-target event in place: the goal that
 * decided to clear the target in the first place tracks its own internal
 * "do I have a target" state separately from the entity's target field, and
 * overwriting the event's outcome desyncs the two - the field says
 * "targeting X" while the goal that's supposed to act on it still thinks it
 * has nothing, so nothing ever fires. Setting the target back through the
 * normal API on a later tick goes through the same path any legitimate
 * target change does (same as vanilla's own retaliation logic), keeping
 * both in sync.
 *
 * <p><b>Range.</b> A skeleton only fights a target within {@link #VIEW_RANGE}
 * blocks and within {@link #MAX_HEIGHT_DIFFERENCE} blocks of its own height;
 * outside that it drops the target. Vanilla only checks range when a
 * skeleton first picks a target - and only its own target-finding goal does
 * even that - so a target handed over another way (being hit, this class's
 * own fallback above) was kept at any distance, and with arrows at 4x speed
 * a skeleton could hit a player 130+ blocks overhead. {@link #enforceRange}
 * checks every skeleton's target every {@link #RANGE_CHECK_TICKS} ticks, well
 * inside the 20-tick bow draw, so an out-of-range draw never gets to fire.
 */
public final class SkeletonListener implements Listener {

    private static final double TARGETING_SPEED_MULTIPLIER = -0.8;
    private static final double SHOT_VELOCITY_MULTIPLIER = 4.0;
    private static final double VIEW_RANGE = 32.0;
    private static final double MAX_HEIGHT_DIFFERENCE = 10.0;
    private static final long RANGE_CHECK_TICKS = 2L;
    private static final int THROTTLE_GOAL_PRIORITY = 0; // must outrank RangedBowAttackGoal to win the MOVE-flag conflict
    private static final int THROTTLE_TICKS_EASY_NORMAL = 120; // 6s
    private static final int THROTTLE_TICKS_HARD = 100; // 5s
    private static final double DAMAGE_EASY = 3.0;
    private static final double DAMAGE_NORMAL = 4.0;
    private static final double DAMAGE_HARD = 5.0;

    private final Plugin plugin;
    private final NamespacedKey targetingSpeedKey;
    private final NamespacedKey throttleGoalKey;

    // Per-skeleton tick of its last shot, read by ThrottleGoal to decide
    // whether a new draw is currently blocked. Keyed by entity UUID rather
    // than held on the goal instance itself so onSkeletonShootBow (which has
    // no reference to the goal) can update it directly.
    private final Map<UUID, Integer> lastShotTick = new HashMap<>();

    // Per-skeleton UUID of whoever (player or tamed pet) last landed a hit,
    // consulted by onSkeletonTarget to decide who to re-target onto when the
    // current target is lost.
    private final Map<UUID, UUID> lastAttacker = new HashMap<>();

    private BukkitTask rangeTask;

    public SkeletonListener(Plugin plugin) {
        this.plugin = plugin;
        this.targetingSpeedKey = new NamespacedKey(plugin, "skeleton_targeting_slow");
        this.throttleGoalKey = new NamespacedKey(plugin, "skeleton_attack_throttle");
    }

    public void start() {
        rangeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::enforceRange, RANGE_CHECK_TICKS, RANGE_CHECK_TICKS);
    }

    public void stop() {
        if (rangeTask != null) {
            rangeTask.cancel();
            rangeTask = null;
        }
    }

    // Dropped through setTarget(null), the same path as any other lost
    // target - so onSkeletonTarget lifts the slow and looks for a fallback,
    // which inRange() keeps from being the same out-of-range target again.
    private void enforceRange() {
        for (World world : Bukkit.getWorlds()) {
            for (Skeleton skeleton : world.getEntitiesByClass(Skeleton.class)) {
                LivingEntity target = skeleton.getTarget();
                if (target != null && !inRange(skeleton, target)) {
                    skeleton.setTarget(null);
                }
            }
        }
    }

    private static boolean inRange(LivingEntity skeleton, LivingEntity target) {
        if (target.getWorld() != skeleton.getWorld()) return false;
        if (Math.abs(target.getY() - skeleton.getY()) > MAX_HEIGHT_DIFFERENCE) return false;
        return target.getLocation().distanceSquared(skeleton.getLocation()) <= VIEW_RANGE * VIEW_RANGE;
    }

    @EventHandler
    public void onSkeletonSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.SKELETON) return;
        if (!(event.getEntity() instanceof Skeleton skeleton)) return;

        AttributeInstance followRange = skeleton.getAttribute(Attribute.FOLLOW_RANGE);
        if (followRange != null) {
            followRange.setBaseValue(VIEW_RANGE);
        }

        GoalKey<Skeleton> key = GoalKey.of(Skeleton.class, throttleGoalKey);
        Bukkit.getMobGoals().addGoal(skeleton, THROTTLE_GOAL_PRIORITY, new ThrottleGoal(key, skeleton));
    }

    @EventHandler
    public void onSkeletonTarget(EntityTargetLivingEntityEvent event) {
        if (event.getEntity().getType() != EntityType.SKELETON) return;
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof LivingEntity skeleton)) return;

        if (event.getTarget() == null && event.getEntity() instanceof Mob mob) {
            scheduleFallbackRetarget(mob);
        }

        AttributeInstance speed = skeleton.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null) return;

        boolean hasModifier = hasModifier(speed, targetingSpeedKey);
        if (event.getTarget() != null && !hasModifier) {
            speed.addModifier(new AttributeModifier(targetingSpeedKey, TARGETING_SPEED_MULTIPLIER,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        } else if (event.getTarget() == null && hasModifier) {
            speed.removeModifier(targetingSpeedKey);
        }
    }

    // One tick later, and through a plain setTarget() call rather than
    // rewriting the event we're currently handling: mutating this event's
    // outcome in place leaves the goal that decided to clear the target
    // still believing it has none, desynced from the entity's actual target
    // field, and nothing ever fires again. A fresh call goes through the
    // same path any legitimate retarget does.
    private void scheduleFallbackRetarget(Mob skeleton) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!skeleton.isValid() || skeleton.getTarget() != null) return;
            LivingEntity fallback = resolveFallbackTarget(skeleton);
            if (fallback != null) {
                skeleton.setTarget(fallback);
            }
        });
    }

    @EventHandler
    public void onSkeletonDamaged(EntityDamageByEntityEvent event) {
        if (event.getEntityType() != EntityType.SKELETON) return;

        Entity attacker = resolveAttacker(event.getDamager());
        if (attacker instanceof Player || (attacker instanceof Tameable tameable && tameable.isTamed())) {
            lastAttacker.put(event.getEntity().getUniqueId(), attacker.getUniqueId());
        }
    }

    // Covers both a skeleton's arrows (the common case) and its melee
    // fallback when a target gets too close for a shot, via the same
    // shooter-resolving helper used for retargeting above - a flat,
    // difficulty-scaled value rather than a reduction off whatever vanilla's
    // own (velocity-dependent, and inflated further by this listener's own
    // 4x shot speed) arrow damage would otherwise land on.
    @SuppressWarnings("deprecation")
    @EventHandler
    public void onSkeletonDealtDamage(EntityDamageByEntityEvent event) {
        if (!(resolveAttacker(event.getDamager()) instanceof Skeleton)) return;

        double damage = switch (event.getEntity().getWorld().getDifficulty()) {
            case HARD -> DAMAGE_HARD;
            case EASY -> DAMAGE_EASY;
            default -> DAMAGE_NORMAL;
        };
        event.setDamage(DamageModifier.BASE, damage);
    }

    // Arrows/tridents report the projectile itself as the damager - resolve
    // back to whoever fired it so a player's own ranged hits count too, not
    // just their pet's melee hits.
    private Entity resolveAttacker(Entity damager) {
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            return shooter;
        }
        return damager;
    }

    // Prefers whoever last landed a hit (matching vanilla's own "retaliate
    // against your attacker" instinct) if they're still around and visible,
    // falling back to the nearest visible player otherwise - e.g. once a
    // pet that just landed the last hit is no longer in view.
    private LivingEntity resolveFallbackTarget(LivingEntity skeleton) {
        UUID lastAttackerId = lastAttacker.get(skeleton.getUniqueId());
        if (lastAttackerId != null) {
            Entity candidate = Bukkit.getEntity(lastAttackerId);
            if (candidate instanceof LivingEntity living && isValidTarget(skeleton, living)) {
                return living;
            }
        }

        LivingEntity nearestPlayer = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (Player player : skeleton.getWorld().getPlayers()) {
            if (!isValidTarget(skeleton, player)) continue;
            double distanceSquared = player.getLocation().distanceSquared(skeleton.getLocation());
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                nearestPlayer = player;
            }
        }
        return nearestPlayer;
    }

    private boolean isValidTarget(LivingEntity skeleton, LivingEntity candidate) {
        if (candidate.isDead() || !candidate.isValid()) return false;
        if (candidate instanceof Player player
                && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)) {
            return false;
        }
        if (!inRange(skeleton, candidate)) return false;
        return skeleton.hasLineOfSight(candidate);
    }

    private boolean hasModifier(AttributeInstance instance, NamespacedKey key) {
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (modifier.getKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onSkeletonShootBow(EntityShootBowEvent event) {
        if (event.getEntity().getType() != EntityType.SKELETON) return;

        lastShotTick.put(event.getEntity().getUniqueId(), Bukkit.getCurrentTick());

        Entity projectile = event.getProjectile();
        double speed = projectile.getVelocity().length() * SHOT_VELOCITY_MULTIPLIER;

        // Vanilla aims with an elevated arc sized for its own (much slower)
        // arrow speed, to compensate for drop over distance. Scaling that
        // whole vector keeps the elevated angle but flattens the actual
        // arc, so the arrow sails high over the target instead of curving
        // back down onto them. Re-aiming straight at the target instead
        // works because a shot this fast barely drops over normal engagement
        // range anyway.
        LivingEntity target = event.getEntity() instanceof Mob mob ? mob.getTarget() : null;
        Vector direction = target != null
                ? target.getEyeLocation().toVector().subtract(projectile.getLocation().toVector()).normalize()
                : projectile.getVelocity().normalize();
        projectile.setVelocity(direction.multiply(speed));
    }

    // True while a skeleton should be held back from starting a new draw -
    // a fixed tick count since its last shot, per the requested exact
    // (not vanilla-approximating) cadence.
    private boolean isThrottled(Skeleton skeleton) {
        Integer shotAtTick = lastShotTick.get(skeleton.getUniqueId());
        if (shotAtTick == null) return false;
        int requiredTicks = skeleton.getWorld().getDifficulty() == Difficulty.HARD
                ? THROTTLE_TICKS_HARD
                : THROTTLE_TICKS_EASY_NORMAL;
        return Bukkit.getCurrentTick() - shotAtTick < requiredTicks;
    }

    private final class ThrottleGoal implements Goal<Skeleton> {

        private static final EnumSet<GoalType> TYPES = EnumSet.of(GoalType.MOVE);

        private final GoalKey<Skeleton> key;
        private final Skeleton skeleton;

        private ThrottleGoal(GoalKey<Skeleton> key, Skeleton skeleton) {
            this.key = key;
            this.skeleton = skeleton;
        }

        @Override
        public boolean shouldActivate() {
            return !skeleton.isHandRaised() && isThrottled(skeleton);
        }

        @Override
        public boolean shouldStayActive() {
            return shouldActivate();
        }

        @Override
        public GoalKey<Skeleton> getKey() {
            return key;
        }

        @Override
        public EnumSet<GoalType> getTypes() {
            return TYPES;
        }
    }
}
