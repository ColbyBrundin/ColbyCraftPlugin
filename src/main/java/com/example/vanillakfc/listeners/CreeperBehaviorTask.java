package com.example.vanillakfc.listeners;

import com.destroystokyo.paper.event.entity.CreeperIgniteEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Per-tick dynamic creeper behavior:
 *
 * <ul>
 *   <li>chase: while a creeper has a player as its target and isn't frozen
 *       (see below), its pathfinder is re-driven toward the player's current
 *       {@link Location} via {@code Pathfinder#moveTo(Location, speed)} at
 *       {@link #CHASE_PATH_SPEED}. Just boosting the MOVEMENT_SPEED
 *       attribute made the entity move much faster than vanilla's own
 *       repathing cadence expects, so it would overshoot path nodes between
 *       recalculations and zig-zag/reverse while "correcting" back onto a
 *       stale path - actively refreshing the path keeps it accurate at
 *       higher speed. This deliberately passes a {@code Location} snapshot
 *       rather than the {@code Player} entity itself: {@code
 *       Pathfinder#moveTo(Entity, speed)} hands continued tracking off to
 *       vanilla's own internal "follow this entity" logic, which appears to
 *       re-target every tick on its own regardless of how often we call it,
 *       and doing that at 3x speed was what caused the creeper to visibly
 *       strafe side to side while approaching - even on flat, obstacle-free
 *       ground. Re-issuing a plain {@code Location} path ourselves (the same
 *       pattern avoid-cats below already used without this problem) gives a
 *       clean, predictable path each time instead.
 *
 *       <p>That fixed the strafing, but recomputing a brand-new path to a
 *       target that shifts by a few centimeters every single tick (a player
 *       walking, or even just turning, moves their reported location
 *       slightly) still made the pathfinder flip-flop between similarly-
 *       costed routes around obstacles - the exact "wildly deviates, goes
 *       back and forth" symptom. {@link #driveToward} throttles this: a new
 *       path is only requested once the destination has moved more than
 *       {@link #REPATH_DISTANCE_THRESHOLD} since the last one, or {@link
 *       #REPATH_MAX_INTERVAL_TICKS} have passed regardless (so a path still
 *       can't go stale at speed) - letting the pathfinder actually finish
 *       executing a route instead of restarting it every tick.</li>
 *   <li>memory: vanilla's own target-acquisition goal forgets a target it
 *       hasn't been able to see for long enough (the same {@code
 *       FORGOT_TARGET} mechanic already patched for skeletons), which
 *       otherwise means a creeper stops dead the instant a player so much as
 *       steps behind a corner, even if they're still right there. {@link
 *       #recallPlayer} keeps a rolling memory of wherever the player was the
 *       last time the creeper actually had them as a live target, and for
 *       {@link #MEMORY_DURATION_TICKS} after the target is lost - provided
 *       the remembered spot is still within {@link #MEMORY_MAX_DISTANCE} of
 *       the creeper's current position, i.e. "slightly out of view but still
 *       close by" - the creeper keeps walking toward that last-known spot.
 *       This only ever drives movement, never restores an actual target:
 *       swelling/igniting still requires vanilla to genuinely re-spot the
 *       player as the creeper closes the gap, so a creeper "remembering"
 *       someone it can't currently see still can't detonate on them.</li>
 *   <li>freeze: while a player is looking directly at the creeper, movement
 *       is zeroed via a MOVEMENT_SPEED modifier rather than {@code
 *       setAI(false)}, so look-control/AI never gets disabled and the
 *       creeper can still turn to face the player. Any player counts, not
 *       just whoever the creeper is currently after - a bystander watching a
 *       creeper chase someone else stops it just as dead, which is the whole
 *       point of being able to cover for a teammate who is busy running.
 *       {@link #haltMovement} makes that stop immediate rather than merely
 *       un-driven: see there for the momentum and queued-route leftovers a
 *       zeroed speed on its own does not clear. This is intentionally
 *       watched-only, not tied to fuse state: an unwatched creeper is meant
 *       to keep chasing (see chase, above) even while charging, so that
 *       running from a primed creeper without keeping eyes on it doesn't
 *       reliably outrun it. The fuse itself is unaffected either way -
 *       vanilla's own SwellGoal decides whether it climbs or decays purely
 *       from live distance and line of sight to the target, independent of
 *       whether this task is currently driving the creeper's movement - so
 *       chasing doesn't prevent the fuse from correctly decaying if a player
 *       actually opens up enough distance, it just means a creeper that can
 *       keep pace stays close enough to keep climbing instead of losing
 *       them for free. (Note {@code isIgnited()} is a different, separate
 *       flag - the persistent "ignited" NBT state set by flint-and-steel or
 *       commands - and stays false for an ordinary proximity-triggered
 *       swell, so it's not usable as a proxy for "currently fusing"
 *       anywhere in this task.)</li>
 *   <li>avoid-cats: vanilla's own creeper-flees-cats goal has no exposed
 *       API to retune its range, so it's replicated here instead - every
 *       tick, the nearest {@link Cat} within {@link #CAT_AVOID_RADIUS}
 *       takes priority over chasing a player, and the pathfinder is driven
 *       to a point beyond that radius, directly away from the cat.</li>
 *   <li>ignite-block: a creeper can't start swelling while a player is
 *       watching it. The proximity-triggered swell (the normal way a
 *       creeper explodes - it doesn't need to be hit, just close to and in
 *       sight of its target) is entirely driven by the creeper having a
 *       target at all: no target means its swell goal never has anyone to
 *       react to. So the primary block is cancelling
 *       {@link EntityTargetLivingEntityEvent} (e.g. from being hit, or from
 *       the vanilla nearest-player goal) while watched, the same
 *       "stop it before it happens" approach used for the skeleton slow -
 *       the target is never set, so the fuse-primed sound/animation never
 *       fires even once. A creeper that already had a target before anyone
 *       started watching it (so there's no target-change event to
 *       intercept - e.g. it was already chasing someone who then turns to
 *       look at it up close) is instead caught by stripping its fuse and
 *       target proactively every tick it's watched and not yet permitted,
 *       run at 1-tick resolution (ahead of entity AI ticking that same
 *       tick) specifically so the swell goal never gets a window where it
 *       both has a target and gets to run - clearing the target (not just
 *       the fuse) also stops the goal from immediately re-requesting one
 *       next tick, which is what caused the fuse-primed sound to loop
 *       before this fallback also cleared the target. Once a fuse is first
 *       observed climbing while unwatched, that creeper is remembered as
 *       "permitted" and left alone while that fuse keeps climbing - even if
 *       someone starts watching partway through, it still detonates on
 *       schedule. The permission ends the moment the fuse stops climbing,
 *       watched or not: a fuse that winds back down (the player backed off
 *       out of range) means the next approach is a fresh one. Fixed
 *       2026-10-07 - it used to be withdrawn only while unwatched, so a
 *       creeper whose fuse wound down while someone was watching it kept
 *       its permission, kept its target, and fused and exploded again the
 *       moment that player walked back up to it, looking right at it. The
 *       explicit-ignition path (flint and steel, dispensers, a plugin
 *       calling {@code ignite()}/{@code setIgnited()}) is separately
 *       covered by cancelling {@link CreeperIgniteEvent} while watched.</li>
 * </ul>
 *
 * <p>Charged (lightning-powered) creepers keep every buff from
 * {@link CreeperListener} same as any other creeper, but are exempt from
 * the whole "stop while watched" system above - they're always treated as
 * unwatched, so looking at one never freezes it, blocks its target, or
 * delays its fuse.
 */
public final class CreeperBehaviorTask implements Listener {

    private static final double VIEW_RANGE = 40.0;
    private static final double VIEW_ANGLE_DEGREES = 90.0;

    // Lowered from 3.0 on 2026-09-24. Measured in play, a chase at 3.0 peaked
    // around 1.2 blocks a tick - roughly four times sprinting - and at that
    // speed every small detour around a block edge became a large, fast
    // swerve. The pathing itself was clean (heading within 26 degrees of the
    // player on 97% of ticks); it was the speed that made the corrections
    // read as twitchy. 2.5 keeps it comfortably faster than a sprinting
    // player while shrinking every swerve proportionally.
    private static final double CHASE_PATH_SPEED = 2.5;

    /** Inside this range the chase stops steering and vanilla's goals take over. */
    private static final double CHASE_HANDOFF_DISTANCE = 2.0;

    /** Only used if a creeper somehow has no follow-range attribute; vanilla's own default. */
    private static final double DEFAULT_FOLLOW_RANGE = 16.0;
    private static final double CAT_AVOID_RADIUS = 12.0;

    // Only request a new path once the destination has moved this far from
    // the last one requested, or this many ticks have passed regardless -
    // recomputing every tick to a target shifting by centimeters made the
    // pathfinder flip-flop between similarly-costed routes around obstacles.
    //
    // Tightened from 2.0/10 on 2026-09-24. At CHASE_PATH_SPEED a creeper
    // covers ~0.57 blocks a tick, so a 10-tick gap let it run ~5.7 blocks -
    // past the end of a path aimed at where the player used to be. It then
    // spent the rest of the gap with no path at all, drifting on momentum and
    // whatever vanilla's own goals did with it: measured over a straight
    // 30-block pursuit of a walking target, 57% of ticks had no live path,
    // the creeper's heading was off by 27-47 degrees on average, and on ~20%
    // of ticks it was moving away from its target. That is the visible
    // jittering-in-odd-directions. At 3 ticks the same chase holds ~14-18
    // degrees, never moves away, and closes 20% sooner. The flip-flop this
    // throttle exists to prevent did not come back: re-measured on an
    // obstacle course of walls and pillars, direction reversals went 3 -> 2.
    private static final double REPATH_DISTANCE_THRESHOLD = 0.75;
    private static final int REPATH_MAX_INTERVAL_TICKS = 3;

    // How long, and how far, a creeper keeps walking toward a player's last
    // known position after vanilla forgets them as a target - "slightly out
    // of view but still close by".
    private static final int MEMORY_DURATION_TICKS = 60; // 3s
    private static final double MEMORY_MAX_DISTANCE = 16.0;

    // A single-tick flicker in the raw line-of-sight check (right as a
    // creeper starts swelling, its view-angle/ray-trace sample can toggle
    // for exactly one tick) let the fuse sneak forward by a tick before
    // watched-freeze caught back up, re-triggering vanilla's one-shot
    // "primed" hiss each time and layering into a louder/longer noise with
    // no explosion. Requiring several consecutive unwatched ticks before
    // actually treating a creeper as unwatched absorbs that flicker; going
    // *into* watched stays immediate since there's no downside to freezing
    // a tick early.
    private static final int UNWATCHED_DEBOUNCE_TICKS = 4;

    // Leaves are drawn with gaps, so a creeper behind a bush or the edge of a
    // canopy is in plain view - but a tree's worth of them does hide it. A
    // sight line counts as clear through up to this many leaf blocks.
    private static final int MAX_LEAVES_SEEN_THROUGH = 2;

    private final Plugin plugin;
    private final NamespacedKey freezeKey;
    private final Predicate<Block> blocksSight;
    private final Set<Material> leaves;
    private final Set<UUID> permittedIgnitions = new HashSet<>();
    private final Map<UUID, Integer> unwatchedStreak = new HashMap<>();
    private final Map<UUID, Boolean> watchedCache = new HashMap<>();
    private final Map<UUID, Integer> lastFuseTicks = new HashMap<>();
    private final Map<UUID, Location> lastPathTarget = new HashMap<>();
    private final Map<UUID, Integer> lastPathTick = new HashMap<>();
    private final Map<UUID, Location> lastKnownPlayerLocation = new HashMap<>();
    private final Map<UUID, Integer> lastKnownPlayerTick = new HashMap<>();
    private BukkitTask task;

    public CreeperBehaviorTask(Plugin plugin) {
        this.plugin = plugin;
        this.freezeKey = new NamespacedKey(plugin, "creeper_freeze");
        // Built here rather than in a static initialiser because the Tag
        // constants below are server registry lookups, and the registries
        // aren't populated until the server is up.
        Set<Material> seeThrough = seeThroughMaterials();
        this.leaves = EnumSet.copyOf(Tag.LEAVES.getValues());
        // Leaves are left to the ray trace's second pass, which counts them.
        this.blocksSight = block -> !seeThrough.contains(block.getType()) && !leaves.contains(block.getType());
    }

    /**
     * Blocks a player can plainly see a creeper through. The line-of-sight
     * check treats these as not obstructing, because a ray trace otherwise
     * stops at anything with a collision box - so a player watching from
     * inside a base, through a window or over a fence, didn't count as
     * watching at all and the creeper carried on as if unobserved. Only
     * blocks you genuinely see through are listed: slabs, stairs and walls
     * really do hide what's behind them and keep blocking. Leaves are
     * handled separately, by count - see {@link #MAX_LEAVES_SEEN_THROUGH}.
     */
    private static Set<Material> seeThroughMaterials() {
        Set<Material> seeThrough = EnumSet.of(
                Material.IRON_BARS,
                Material.SCAFFOLDING,
                Material.BARRIER,
                Material.LIGHT,
                // Added 2026-10-07: cages and grates you plainly see through,
                // and the two translucent blocks.
                Material.SPAWNER,
                Material.TRIAL_SPAWNER,
                Material.VAULT,
                Material.MANGROVE_ROOTS,
                Material.SLIME_BLOCK,
                Material.HONEY_BLOCK
        );
        seeThrough.addAll(Tag.IMPERMEABLE.getValues()); // every glass block, tinted included
        seeThrough.addAll(Tag.ICE.getValues());
        seeThrough.addAll(Tag.FENCES.getValues());
        seeThrough.addAll(Tag.FENCE_GATES.getValues());
        // Glass panes, chains and copper grates have no vanilla tag of their
        // own, and matching the name picks up every dyed pane and every
        // weathering and waxed copper variant without listing them all out.
        for (Material material : Material.values()) {
            if (material.isLegacy()) continue;
            String name = material.name();
            if (name.endsWith("GLASS_PANE") || name.endsWith("_CHAIN") || name.endsWith("COPPER_GRATE")) {
                seeThrough.add(material);
            }
        }
        return seeThrough;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        Set<UUID> liveCreepers = new HashSet<>();
        for (World world : Bukkit.getWorlds()) {
            for (Creeper creeper : world.getEntitiesByClass(Creeper.class)) {
                liveCreepers.add(creeper.getUniqueId());
                boolean watched = resolveWatched(creeper);
                updateIgniteBlock(creeper, watched);
                // After the ignite block, so a fuse it just snuffed reads as out.
                CreeperListener.applyFuseHealth(creeper);
                boolean frozen = updateFreeze(creeper, watched);
                if (!frozen && creeper.getTarget() == null) {
                    reacquireTarget(creeper);
                }
                Cat nearestCat = frozen ? null : findNearestCat(creeper);
                if (nearestCat != null) {
                    driveToward(creeper, fleeDestination(creeper, nearestCat));
                } else if (!frozen) {
                    if (creeper.getTarget() instanceof Player player) {
                        rememberPlayer(creeper, player);
                        driveToward(creeper, player.getLocation());
                    } else {
                        Location remembered = recallPlayer(creeper);
                        if (remembered != null) {
                            driveToward(creeper, remembered);
                        }
                    }
                }
            }
        }
        permittedIgnitions.retainAll(liveCreepers);
        unwatchedStreak.keySet().retainAll(liveCreepers);
        watchedCache.keySet().retainAll(liveCreepers);
        lastFuseTicks.keySet().retainAll(liveCreepers);
        lastPathTarget.keySet().retainAll(liveCreepers);
        lastPathTick.keySet().retainAll(liveCreepers);
        lastKnownPlayerLocation.keySet().retainAll(liveCreepers);
        lastKnownPlayerTick.keySet().retainAll(liveCreepers);
    }

    // Throttled path requests: skip re-pathing unless the destination has
    // moved meaningfully or the last path is old enough to risk going stale,
    // instead of restarting the route fresh every single tick.
    private void driveToward(Creeper creeper, Location destination) {
        UUID id = creeper.getUniqueId();

        // Close enough that steering does more harm than good: at
        // CHASE_PATH_SPEED the creeper covers most of this distance in a
        // single tick, so it overshoots whatever point it is sent to and
        // spends the last couple of blocks correcting back and forth around
        // its target. Vanilla's own melee/swell goals handle contact range
        // perfectly well, so the chase simply hands over here - measured, it
        // roughly halves the close-range thrashing. The throttle state is
        // dropped with it, so the moment the player opens the gap again the
        // next tick re-paths immediately rather than waiting out the interval.
        if (destination.getWorld() == creeper.getWorld()
                && destination.distanceSquared(creeper.getLocation())
                    < CHASE_HANDOFF_DISTANCE * CHASE_HANDOFF_DISTANCE) {
            lastPathTarget.remove(id);
            lastPathTick.remove(id);
            return;
        }

        Location lastTarget = lastPathTarget.get(id);
        Integer lastTick = lastPathTick.get(id);
        int now = Bukkit.getCurrentTick();

        boolean shouldRepath = lastTarget == null
                || lastTarget.getWorld() != destination.getWorld()
                || lastTarget.distanceSquared(destination) > REPATH_DISTANCE_THRESHOLD * REPATH_DISTANCE_THRESHOLD
                || lastTick == null
                || now - lastTick >= REPATH_MAX_INTERVAL_TICKS;
        if (!shouldRepath) return;

        creeper.getPathfinder().moveTo(destination, CHASE_PATH_SPEED);
        lastPathTarget.put(id, destination);
        lastPathTick.put(id, now);
    }

    /**
     * Re-targets the nearest player in sight the moment a creeper stops being
     * watched.
     *
     * <p>Freezing a watched creeper works by stripping its target every tick
     * (that is what keeps the fuse from ever starting), so when the player
     * looks away it has nothing to chase and has to wait for vanilla's own
     * targeting goal to notice them again. That goal only retries every so
     * often, and vanilla's random-stroll goal owns the creeper in the
     * meantime: measured over a real chase, the creeper spent 6.6 seconds
     * targetless with the player 5-32 blocks away, crawling at 0.06 blocks a
     * tick and walking *away* from them on 87 of 88 ticks, in gaps as long as
     * 3.2 seconds. That is the "wanders off in a random direction" this task
     * exists to prevent, and it was this task causing it.
     *
     * <p>Line of sight is required, same as vanilla's goal, so this only
     * restores the target vanilla would have picked anyway - just without the
     * wait. A creeper that genuinely lost the player behind a wall still has
     * to re-find them, and {@link #recallPlayer} covers the near misses.
     */
    private void reacquireTarget(Creeper creeper) {
        AttributeInstance followRange = creeper.getAttribute(Attribute.FOLLOW_RANGE);
        double range = followRange == null ? DEFAULT_FOLLOW_RANGE : followRange.getValue();

        Player nearest = null;
        double nearestDistance = range * range;
        for (Player player : creeper.getWorld().getPlayers()) {
            if (!isTargetable(player)) continue;
            double distance = player.getLocation().distanceSquared(creeper.getLocation());
            if (distance < nearestDistance && creeper.hasLineOfSight(player)) {
                nearestDistance = distance;
                nearest = player;
            }
        }
        if (nearest != null) {
            creeper.setTarget(nearest);
        }
    }

    // Creative and Spectator players are no more a target here than they are
    // to vanilla's own targeting.
    private static boolean isTargetable(Player player) {
        if (player.isDead() || !player.isValid()) return false;
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    private void rememberPlayer(Creeper creeper, Player player) {
        UUID id = creeper.getUniqueId();
        lastKnownPlayerLocation.put(id, player.getLocation());
        lastKnownPlayerTick.put(id, Bukkit.getCurrentTick());
    }

    // Null once the memory has expired, gone stale past its time window, or
    // the remembered spot is no longer close enough to still count as
    // "nearby" - in any of those cases the creeper just has nothing left to
    // go on and stops, same as vanilla would.
    private Location recallPlayer(Creeper creeper) {
        UUID id = creeper.getUniqueId();
        Location remembered = lastKnownPlayerLocation.get(id);
        Integer seenTick = lastKnownPlayerTick.get(id);
        if (remembered == null || seenTick == null) return null;

        if (Bukkit.getCurrentTick() - seenTick > MEMORY_DURATION_TICKS) {
            lastKnownPlayerLocation.remove(id);
            lastKnownPlayerTick.remove(id);
            return null;
        }
        if (remembered.getWorld() != creeper.getWorld()) return null;
        if (remembered.distanceSquared(creeper.getLocation()) > MEMORY_MAX_DISTANCE * MEMORY_MAX_DISTANCE) return null;
        return remembered;
    }

    /**
     * Debounces the raw line-of-sight check: entering "watched" is
     * immediate, but a creeper only counts as "unwatched" again after
     * {@link #UNWATCHED_DEBOUNCE_TICKS} consecutive unwatched ticks, so a
     * one-tick flicker in the raw check can't open a window for the fuse to
     * advance. Charged creepers bypass this entirely - they're always
     * unwatched, per class.
     */
    private boolean resolveWatched(Creeper creeper) {
        UUID id = creeper.getUniqueId();
        boolean watched;
        if (creeper.isPowered()) {
            unwatchedStreak.remove(id);
            watched = false;
        } else if (isWatchedByAnyPlayer(creeper)) {
            unwatchedStreak.remove(id);
            watched = true;
        } else {
            int streak = unwatchedStreak.merge(id, 1, Integer::sum);
            watched = streak < UNWATCHED_DEBOUNCE_TICKS;
        }
        watchedCache.put(id, watched);
        return watched;
    }

    // onCreeperTarget/onCreeperIgnite fire whenever vanilla's goal selector
    // reacts - which can be a different instant than this tick's sweep above,
    // since we clear the target every watched tick and the goal retries
    // immediately. Reading the cached, debounced result here (instead of
    // each handler recomputing isWatchedByAnyPlayer fresh) keeps every check
    // against a creeper agreeing within the tick, closing the exact race
    // that let the fuse advance for a tick and re-trigger vanilla's primed
    // hiss even with the debounce above in place.
    private boolean isCurrentlyWatched(Creeper creeper) {
        Boolean cached = watchedCache.get(creeper.getUniqueId());
        return cached != null ? cached : isWatchedByAnyPlayer(creeper);
    }

    /**
     * @return true if the creeper is currently frozen (movement zeroed).
     */
    private boolean updateFreeze(Creeper creeper, boolean watched) {
        AttributeInstance speed = creeper.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null) {
            return false;
        }
        boolean hasModifier = hasModifier(speed, freezeKey);

        if (watched && !hasModifier) {
            speed.addModifier(new AttributeModifier(freezeKey, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            haltMovement(creeper);
        } else if (!watched && hasModifier) {
            speed.removeModifier(freezeKey);
        }
        return watched;
    }

    // A zeroed speed attribute stops the creeper being *driven* anywhere, but
    // it doesn't undo what it was already doing. Two leftovers survive it: the
    // momentum of a chase at CHASE_PATH_SPEED, which friction alone takes the
    // better part of a second to bleed off while the creeper keeps drifting at
    // its target, and the route it was following, still queued up to resume
    // the moment the freeze lifts. Clearing both on the tick the freeze goes
    // on is what makes looking at a creeper read as an actual stop.
    //
    // Only on that transition, not every frozen tick: a watched creeper is
    // very often a creeper being hit, and zeroing its velocity continuously
    // would quietly make it immune to knockback as well.
    private void haltMovement(Creeper creeper) {
        creeper.getPathfinder().stopPathfinding();

        // Vertical velocity is left alone, so a creeper frozen mid-air or
        // mid-jump still falls instead of hanging there.
        Vector velocity = creeper.getVelocity();
        creeper.setVelocity(new Vector(0.0, velocity.getY(), 0.0));

        // The repath throttle goes too. Without this the first unfrozen tick
        // would see an unchanged destination and a recent timestamp, and sit
        // still for up to REPATH_MAX_INTERVAL_TICKS before re-issuing the path
        // that stopPathfinding() just threw away.
        UUID id = creeper.getUniqueId();
        lastPathTarget.remove(id);
        lastPathTick.remove(id);
    }

    private void updateIgniteBlock(Creeper creeper, boolean watched) {
        UUID id = creeper.getUniqueId();
        int fuseTicks = creeper.getFuseTicks();
        Integer previous = lastFuseTicks.put(id, fuseTicks);
        if (!watched) {
            // Only an unwatched fuse that's actively climbing earns the
            // detonate-even-if-watched-later permission. One that's holding
            // or decaying (e.g. the player broke range/LOS and it's winding
            // back down) has its permission revoked immediately, rather
            // than waiting for it to bottom out at exactly 0 - otherwise a
            // fuse interrupted at, say, 5 and never quite reaching 0 before
            // the player turns back to watch it stays "permitted" from the
            // earlier attempt and detonates anyway, even though this is
            // really a fresh approach that should be frozen like any other.
            if (fuseTicks > 0 && (previous == null || fuseTicks >= previous)) {
                permittedIgnitions.add(id);
            } else {
                permittedIgnitions.remove(id);
            }
            return;
        }
        if (permittedIgnitions.contains(id)) {
            // Already fusing from before anyone watched - let it detonate, but
            // only while that fuse is still climbing. Once it holds or winds
            // down, the attempt is over and this watched creeper is stopped
            // like any other.
            if (fuseTicks > 0 && (previous == null || fuseTicks > previous)) {
                return;
            }
            permittedIgnitions.remove(id);
        }
        // Not yet permitted and currently watched: strip the fuse and the
        // target *before* this tick's entity AI runs (this task runs early
        // in the tick, ahead of entity ticking), so the swell goal never
        // sees a target to react to and never gets a chance to set the fuse
        // above 0 in the first place - not just reset a moment later.
        boolean wasFusing = creeper.getFuseTicks() > 0;
        if (wasFusing) {
            creeper.setFuseTicks(0);
            lastFuseTicks.put(id, 0);
        }
        if (creeper.getTarget() != null) {
            creeper.setTarget(null);
        }
        // Vanilla broadcasts the "primed" hiss server-side the instant the
        // fuse first ticks up from 0 - that packet is already gone before
        // we get a chance to react here, and resetting fuseTicks just
        // re-arms the same 0-to-positive trigger for the next attempt. If
        // this gets caught more than once in quick succession, each
        // retrigger's hiss stacks on the still-playing previous one and
        // comes out distorted. Cutting off any in-flight copy every time we
        // catch a live fuse stops them from ever overlapping.
        if (wasFusing) {
            stopPrimedSound(creeper);
        }
    }

    private void stopPrimedSound(Creeper creeper) {
        World world = creeper.getWorld();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() == world) {
                player.stopSound(Sound.ENTITY_CREEPER_PRIMED, SoundCategory.HOSTILE);
            }
        }
    }

    @EventHandler
    public void onCreeperTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper)) {
            return;
        }
        if (creeper.isPowered()) {
            return;
        }
        if (event.getTarget() == null) {
            return; // losing a target is always fine
        }
        if (isCurrentlyWatched(creeper)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCreeperIgnite(CreeperIgniteEvent event) {
        if (!event.isIgnited()) {
            return;
        }
        if (event.getEntity().isPowered()) {
            return;
        }
        if (isCurrentlyWatched(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    private Cat findNearestCat(Creeper creeper) {
        Cat nearest = null;
        double nearestDistanceSquared = Double.MAX_VALUE;
        for (Cat cat : creeper.getWorld().getNearbyEntitiesByType(Cat.class, creeper.getLocation(), CAT_AVOID_RADIUS)) {
            double distanceSquared = cat.getLocation().distanceSquared(creeper.getLocation());
            if (distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                nearest = cat;
            }
        }
        return nearest;
    }

    private Location fleeDestination(Creeper creeper, Cat cat) {
        Vector away = creeper.getLocation().toVector().subtract(cat.getLocation().toVector());
        away = away.lengthSquared() < 0.0001 ? new Vector(1, 0, 0) : away.normalize();
        return creeper.getLocation().add(away.multiply(CAT_AVOID_RADIUS));
    }

    private boolean hasModifier(AttributeInstance instance, NamespacedKey key) {
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (modifier.getKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    // Deliberately every online player in the world, with no reference to the
    // creeper's own target: the creeper's aggro has no bearing on who is
    // capable of looking at it.
    private boolean isWatchedByAnyPlayer(Creeper creeper) {
        List<Location> samplePoints = getSamplePoints(creeper);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() != creeper.getWorld()) {
                continue;
            }
            Location eye = player.getEyeLocation();
            for (Location point : samplePoints) {
                if (canSee(eye, point)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The 8 corners of the creeper's hitbox plus its center, so that a
     * player who can see just the head or feet poking out from behind cover
     * still counts as "watching" it, not only a clean line to dead-center.
     */
    private List<Location> getSamplePoints(Creeper creeper) {
        BoundingBox box = creeper.getBoundingBox();
        World world = creeper.getWorld();
        List<Location> points = new ArrayList<>(9);
        points.add(new Location(world, box.getCenterX(), box.getCenterY(), box.getCenterZ()));
        double[] xs = {box.getMinX(), box.getMaxX()};
        double[] ys = {box.getMinY(), box.getMaxY()};
        double[] zs = {box.getMinZ(), box.getMaxZ()};
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    points.add(new Location(world, x, y, z));
                }
            }
        }
        return points;
    }

    private boolean canSee(Location eye, Location point) {
        if (eye.distanceSquared(point) > VIEW_RANGE * VIEW_RANGE) {
            return false;
        }
        Vector toTarget = point.toVector().subtract(eye.toVector());
        double distance = toTarget.length();
        if (distance < 0.001) {
            return true;
        }
        Vector direction = toTarget.clone().normalize();
        double angle = Math.toDegrees(eye.getDirection().normalize().angle(direction));
        if (angle > VIEW_ANGLE_DEGREES) {
            return false;
        }

        RayTraceResult hit = eye.getWorld().rayTraceBlocks(
                eye, direction, distance, FluidCollisionMode.NEVER, true, blocksSight);
        return hit == null && leavesAlong(eye, direction, distance) <= MAX_LEAVES_SEEN_THROUGH;
    }

    // Leaf blocks are full cubes, so every one the line passes through is in
    // the way. Stops counting once past the limit.
    private int leavesAlong(Location eye, Vector direction, double distance) {
        int count = 0;
        try {
            BlockIterator blocks = new BlockIterator(eye.getWorld(), eye.toVector(), direction, 0.0,
                    (int) Math.ceil(distance));
            while (blocks.hasNext() && count <= MAX_LEAVES_SEEN_THROUGH) {
                if (leaves.contains(blocks.next().getType())) count++;
            }
        } catch (IllegalStateException e) {
            // BlockIterator's own edge case for a start exactly on a block
            // boundary. The solid-block trace already passed, so count it seen.
            return 0;
        }
        return count;
    }
}
