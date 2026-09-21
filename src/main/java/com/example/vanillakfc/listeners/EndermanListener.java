package com.example.vanillakfc.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bundles the Enderman-specific customizations:
 * <ul>
 *   <li>Block pickup prevention (terrain griefing) - Endermen can no longer
*   pick up any block.</li>
 *   <li>A flat attack damage reduction on top of whatever vanilla's
 *   difficulty-scaled melee damage already is.</li>
 *   <li>A chance for a player's melee hit to trigger a full dodge: instead
 *   of taking that hit, the Enderman teleports to a nearby spot biased to
 *   land behind the attacker.</li>
 *   <li>An elevated-hit counter: any hit landed while the player is standing
 *   at least one block above the Enderman's own feet has a
 *   {@link #ELEVATED_COUNTER_CHANCE} chance to trigger a hard punish - the
 *   Enderman teleports directly behind the player, lands a hit with enough
 *   knockback to shove them off their perch, then teleports back to where
 *   it started a moment later. Punishes the classic "pillar up and hit it
 *   from above" cheese without requiring any reachability analysis.</li>
 * </ul>
 */
public final class EndermanListener implements Listener {

    private static final double ATTACK_DAMAGE_REDUCTION = 2.0;

    private static final double DODGE_TELEPORT_CHANCE = 0.50;
    private static final double MIN_DODGE_DISTANCE = 3.0;
    private static final double MAX_DODGE_DISTANCE = 7.0;
    private static final double BEHIND_BIAS_CHANCE = 0.65;
    private static final double BEHIND_CONE_HALF_ANGLE_DEGREES = 30.0;
    private static final int VERTICAL_SEARCH_RADIUS = 4;
    private static final int REQUIRED_CLEARANCE_HEIGHT = 3;
    private static final int MAX_DESTINATION_ATTEMPTS = 16;
    private static final int TELEPORT_PARTICLE_COUNT = 24;

    // Chance that a hit landed from above the Enderman's own feet triggers
    // the counter-move instead of applying normally.
    private static final double ELEVATED_COUNTER_CHANCE = 0.50;
    // Blocks, center-to-center - both hitboxes are ~0.6 wide, so this avoids
    // overlap while staying melee-tight.
    private static final double COUNTER_TELEPORT_DISTANCE = 1.2;
    // Enderman's own knockback attribute is 0, so this single call carries the
    // entire shove; only ~0.5 blocks of lateral travel is needed to clear a
    // 1-wide pillar. Starting point - tune in-game.
    private static final double COUNTER_KNOCKBACK_STRENGTH = 1.8;
    // 0.4s: long enough that the hit is visible before the Enderman vanishes again.
    private static final long COUNTER_RETURN_DELAY_TICKS = 8L;

    private final Plugin plugin;
    private final Set<UUID> pendingCounterReturn = new HashSet<>();

    public EndermanListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEndermanChangeBlock(EntityChangeBlockEvent event) {
        if (event.getEntityType() != EntityType.ENDERMAN) return;
        if (event.getTo() != Material.AIR) return; // AIR target = placement, leave it
        event.setCancelled(true);
    }

    // Vanilla scales Enderman melee damage by difficulty internally (Easy
    // 4.5, Normal 7, Hard 10.5) before this event ever fires, so a flat
    // subtraction from the BASE modifier tracks whatever difficulty is
    // active without branching on it here.
    @SuppressWarnings("deprecation")
    @EventHandler
    public void onEndermanAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager().getType() != EntityType.ENDERMAN) return;
        double base = event.getDamage(DamageModifier.BASE);
        event.setDamage(DamageModifier.BASE, Math.max(0.0, base - ATTACK_DAMAGE_REDUCTION));
    }

    @EventHandler
    public void onEndermanDamagedByPlayer(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;
        if (event.getEntityType() != EntityType.ENDERMAN) return;
        if (!(event.getDamager() instanceof Player player)) return;

        Enderman enderman = (Enderman) event.getEntity();

        // Mid return-flight from a just-executed counter-move - let this hit
        // through untouched so a lucky double-hit can't dodge-teleport the
        // Enderman away and step on the "appeared, hit, vanished" beat.
        if (pendingCounterReturn.contains(enderman.getUniqueId())) return;

        // The player's own damage always still applies here; this only ever
        // skips this hit's dodge-roll below, never cancels the event.
        if (isHitFromAbove(enderman, player)
                && ThreadLocalRandom.current().nextDouble() < ELEVATED_COUNTER_CHANCE
                && executeElevatedCounter(enderman, player)) {
            return;
        }

        if (ThreadLocalRandom.current().nextDouble() >= DODGE_TELEPORT_CHANCE) return;

        DodgeDestination dodge = findDodgeDestination(enderman, player);
        if (dodge == null) return;

        Location origin = enderman.getLocation().clone();
        if (!enderman.teleport(dodge.location())) return;

        playTeleportEffects(enderman.getWorld(), origin, dodge.behindBiased());
        playTeleportEffects(enderman.getWorld(), dodge.location(), dodge.behindBiased());
        event.setCancelled(true);
    }

    private record DodgeOffset(Vector vector, boolean behindBiased) {}

    private record DodgeDestination(Location location, boolean behindBiased) {}

    // "Above the legs block" - the player's own feet block is higher than
    // the Enderman's feet block, i.e. the hit was struck downward onto it.
    private boolean isHitFromAbove(Enderman enderman, Player player) {
        return player.getLocation().getBlockY() > enderman.getLocation().getBlockY();
    }

    // Teleports the Enderman directly behind the player - deliberately
    // bypassing tryColumn's footing requirement, since landing in open air
    // right next to the player is the entire point - lands a hit with
    // enough knockback to carry the whole shove, then schedules a
    // short-delayed return to the spot it started from. Returns false
    // without side effects if the space behind the player is blocked by
    // terrain, in which case the hit just applies normally.
    private boolean executeElevatedCounter(Enderman enderman, Player player) {
        World world = enderman.getWorld();
        Location origin = enderman.getLocation().clone();
        Vector behind = behindUnitVector(player.getLocation());

        Location destination = player.getLocation().clone().add(behind.clone().multiply(COUNTER_TELEPORT_DISTANCE));
        if (!isCounterDestinationClear(destination)) return false;
        destination.setDirection(behind.clone().multiply(-1));

        if (!enderman.teleport(destination)) return false;

        playTeleportEffects(world, origin, true);
        playTeleportEffects(world, destination, true);

        enderman.attack(player);
        player.knockback(COUNTER_KNOCKBACK_STRENGTH, behind.getX(), behind.getZ());

        UUID endermanId = enderman.getUniqueId();
        pendingCounterReturn.add(endermanId);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingCounterReturn.remove(endermanId);
            if (!enderman.isValid()) return;

            Location current = enderman.getLocation().clone();
            if (!enderman.teleport(origin)) return;

            playTeleportEffects(world, current, true);
            playTeleportEffects(world, origin, true);
        }, COUNTER_RETURN_DELAY_TICKS);

        return true;
    }

    // Unlike tryColumn, this never requires solid footing - the Enderman is
    // meant to hang in open air right next to the player. It only guards
    // against teleporting into solid terrain (e.g. a wall directly behind a
    // pillar built against a cliff face) and the world border/height limits.
    private boolean isCounterDestinationClear(Location destination) {
        World world = destination.getWorld();
        int x = destination.getBlockX();
        int baseY = destination.getBlockY();
        int z = destination.getBlockZ();

        if (baseY < world.getMinHeight() || baseY + REQUIRED_CLEARANCE_HEIGHT >= world.getMaxHeight()) return false;
        if (!world.getWorldBorder().isInside(destination)) return false;

        for (int i = 0; i < REQUIRED_CLEARANCE_HEIGHT; i++) {
            Block space = world.getBlockAt(x, baseY + i, z);
            if (space.isLiquid()) return false;
            if (space.getType() == Material.FIRE || space.getType() == Material.SOUL_FIRE) return false;
            if (!space.isPassable()) return false;
        }
        return true;
    }

    private DodgeDestination findDodgeDestination(Enderman enderman, Player player) {
        World world = enderman.getWorld();
        Location playerLocation = player.getLocation();

        for (int attempt = 0; attempt < MAX_DESTINATION_ATTEMPTS; attempt++) {
            DodgeOffset offset = randomBehindBiasedOffset(playerLocation);
            double x = playerLocation.getX() + offset.vector().getX();
            double z = playerLocation.getZ() + offset.vector().getZ();

            Location destination = findVerticalSlot(world, x, z, playerLocation.getY(), enderman);
            if (destination != null) return new DodgeDestination(destination, offset.behindBiased());
        }
        return null;
    }

    private DodgeOffset randomBehindBiasedOffset(Location playerLocation) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double distance = MIN_DODGE_DISTANCE + random.nextDouble() * (MAX_DODGE_DISTANCE - MIN_DODGE_DISTANCE);

        Vector behind = behindUnitVector(playerLocation);

        boolean behindBiased = random.nextDouble() < BEHIND_BIAS_CHANCE;
        double angleDegrees = behindBiased
                ? (random.nextDouble() * 2 - 1) * BEHIND_CONE_HALF_ANGLE_DEGREES
                : random.nextDouble() * 360.0 - 180.0;

        Vector vector = behind.rotateAroundY(Math.toRadians(angleDegrees)).multiply(distance);
        return new DodgeOffset(vector, behindBiased);
    }

    // Shared by the dodge mechanic's cone-bias search (which rotates/scales
    // this) and the pillar counter (which uses it directly, unrotated, both
    // to place the Enderman and as the knockback source direction).
    private Vector behindUnitVector(Location playerLocation) {
        double yawRad = Math.toRadians(playerLocation.getYaw());
        return new Vector(Math.sin(yawRad), 0, -Math.cos(yawRad));
    }

    // Scans outward from the player's own feet height (closest first) for a
    // column with solid, non-liquid footing and enough clear, non-liquid,
    // non-fire space above it to fit an Enderman.
    private Location findVerticalSlot(World world, double x, double z, double anchorY, Enderman enderman) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int anchorBlockY = (int) Math.floor(anchorY);

        for (int radius = 0; radius <= VERTICAL_SEARCH_RADIUS; radius++) {
            int[] offsets = radius == 0 ? new int[] {0} : new int[] {radius, -radius};
            for (int dy : offsets) {
                Location candidate = tryColumn(world, blockX, anchorBlockY + dy, blockZ);
                if (candidate != null) {
                    Location current = enderman.getLocation();
                    candidate.setYaw(current.getYaw());
                    candidate.setPitch(current.getPitch());
                    return candidate;
                }
            }
        }
        return null;
    }

    private Location tryColumn(World world, int x, int floorY, int z) {
        if (floorY < world.getMinHeight() || floorY + REQUIRED_CLEARANCE_HEIGHT >= world.getMaxHeight()) return null;

        Block floor = world.getBlockAt(x, floorY, z);
        if (!floor.getType().isSolid() || floor.isLiquid()) return null;

        for (int i = 1; i <= REQUIRED_CLEARANCE_HEIGHT; i++) {
            Block space = world.getBlockAt(x, floorY + i, z);
            if (space.isLiquid()) return null;
            if (space.getType() == Material.FIRE || space.getType() == Material.SOUL_FIRE) return null;
            if (!space.isPassable()) return null;
        }

        Location location = new Location(world, x + 0.5, floorY + 1, z + 0.5);
        if (!world.getWorldBorder().isInside(location)) return null;
        return location;
    }

    // The custom clip only plays for the 65%-chance "forced behind" branch,
    // not for a dodge that happens to land behind the player via the 35%
    // uniform-angle fallback - it marks the deliberate, biased dodge
    // specifically, not just its outcome.
    private void playTeleportEffects(World world, Location location, boolean behindBiased) {
        world.spawnParticle(Particle.PORTAL, location.clone().add(0, 1.0, 0), TELEPORT_PARTICLE_COUNT, 0.3, 0.5, 0.3, 0.0);
        if (behindBiased) {
            // Matches the vanilla call below in using the no-category
            // overload (SoundCategory.MASTER) rather than HOSTILE, so this
            // isn't silenced independently by a "Hostile Creatures" slider
            // the vanilla teleport sound isn't subject to.
            world.playSound(location, EndermanDodgeSoundPackListener.CUSTOM_TELEPORT_SOUND_KEY, 1.0f, 1.0f);
        } else {
            world.playSound(location, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        }
    }
}
