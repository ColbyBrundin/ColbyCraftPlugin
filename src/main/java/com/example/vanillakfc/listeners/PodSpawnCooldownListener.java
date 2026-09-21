package com.example.vanillakfc.listeners;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Enemy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Once a hostile pod naturally spawns somewhere, no other hostile pod may
 * naturally spawn within {@link #RADIUS} blocks of that X/Z column for the
 * next five minutes - any species blocks any other species, and height
 * doesn't matter (a spawn 60 blocks straight down still locks the surface
 * spot above it). Only {@link CreatureSpawnEvent.SpawnReason#NATURAL} is
 * considered, which is what excludes monster-spawner spawns, breeding, and
 * spawn eggs (they all use different spawn reasons) without needing to
 * check for them explicitly.
 *
 * <p>Split across two priorities so this composes with the existing
 * per-species pod listeners ({@link CreeperListener}, {@link
 * ZombiePodListener}) regardless of registration order: the deny check runs
 * at {@link EventPriority#LOW}, before those listeners' own pod-size/chance
 * logic, so a cooldown-denied spawn is already cancelled by the time they
 * see it. The record step runs at {@link EventPriority#MONITOR}, after
 * everything else has had a chance to cancel the event for unrelated
 * reasons, so a cooldown zone is only ever created for a spawn that actually
 * went through.
 */
public final class PodSpawnCooldownListener implements Listener {

    private static final long WINDOW_MILLIS = 5 * 60 * 1000L;
    private static final double RADIUS = 6.0;
    private static final double RADIUS_SQUARED = RADIUS * RADIUS;

    private static final class SpawnRecord {
        final World world;
        final double x;
        final double z;
        final long expiresAt;

        SpawnRecord(Location location, long expiresAt) {
            this.world = location.getWorld();
            this.x = location.getX();
            this.z = location.getZ();
            this.expiresAt = expiresAt;
        }

        // Deliberately ignores Y: the cooldown locks an X/Z column across all
        // heights, so a spawn deep underground still blocks the surface spot
        // directly above it.
        boolean isWithinRadius(Location location) {
            if (!world.equals(location.getWorld())) {
                return false;
            }
            double dx = x - location.getX();
            double dz = z - location.getZ();
            return (dx * dx + dz * dz) <= RADIUS_SQUARED;
        }
    }

    // Ordered by expiry since records are always appended with the current
    // time, so pruning is just polling stale entries off the head instead of
    // scanning the whole deque.
    private final Deque<SpawnRecord> recentPodSpawns = new ArrayDeque<>();

    @EventHandler(priority = EventPriority.LOW)
    public void onNaturalHostileSpawnAttempt(CreatureSpawnEvent event) {
        if (!isEligible(event)) {
            return;
        }

        long now = System.currentTimeMillis();
        pruneExpired(now);

        for (SpawnRecord record : recentPodSpawns) {
            if (record.isWithinRadius(event.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onNaturalHostileSpawnResolved(CreatureSpawnEvent event) {
        if (!isEligible(event)) {
            return;
        }

        long now = System.currentTimeMillis();
        pruneExpired(now);
        recentPodSpawns.addLast(new SpawnRecord(event.getLocation(), now + WINDOW_MILLIS));
    }

    private static boolean isEligible(CreatureSpawnEvent event) {
        if (event.isCancelled()) {
            return false;
        }
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return false;
        }
        return event.getEntity() instanceof Enemy;
    }

    private void pruneExpired(long now) {
        while (!recentPodSpawns.isEmpty() && recentPodSpawns.peekFirst().expiresAt <= now) {
            recentPodSpawns.pollFirst();
        }
    }
}
