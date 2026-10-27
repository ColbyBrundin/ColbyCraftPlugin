package com.example.vanillakfc.listeners;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Creepers have 60 max health (vanilla 20), see 32 blocks out and step/jump
 * over terrain much more readily (chase speed is handled dynamically in
 * {@link CreeperBehaviorTask}, since it only applies while actively closing
 * in on a player). Natural spawns are throttled and capped to pods of 1-2.
 *
 * <p>While its fuse is lit a creeper drops to 1 max health, so any hit kills
 * it, and it only goes back to a full 60 once the fuse has wound all the way
 * back down to 0 - a fuse that's decaying but not yet out still counts as
 * lit. {@link CreeperBehaviorTask} applies this every tick
 * ({@link #applyFuseHealth}); that runs before entities tick, so a fuse that
 * lights during a tick wouldn't be caught until the next one, and
 * {@link #onCreeperDamaged} closes that gap by dropping a creeper with a lit
 * fuse to 1 before any hit lands.
 */
public final class CreeperListener implements Listener {

    private static final double MAX_HEALTH = 60.0;
    private static final double FUSE_LIT_MAX_HEALTH = 1.0;
    private static final double FOLLOW_RANGE = 32.0;
    private static final double STEP_HEIGHT = 1.2; // clears a full block without needing to jump
    private static final double JUMP_STRENGTH_MULTIPLIER = 1.5;
    // Vanilla's own explodeCreeper() separately doubles this again for
    // powered creepers, so this yields 4 normal / 8 charged.
    private static final int EXPLOSION_RADIUS = 4;

    private static final double NATURAL_SPAWN_KEEP_CHANCE = 0.35;
    private static final double POD_RADIUS = 24.0;
    private static final int POD_MAX_SIZE = 2;
    private static final double SECOND_IN_POD_CHANCE = 0.5;

    @EventHandler
    public void onCreeperSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.CREEPER) {
            return;
        }
        Creeper creeper = (Creeper) event.getEntity();

        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) {
            if (ThreadLocalRandom.current().nextDouble() > NATURAL_SPAWN_KEEP_CHANCE) {
                event.setCancelled(true);
                return;
            }

            long nearby = creeper.getWorld().getNearbyEntitiesByType(Creeper.class, creeper.getLocation(), POD_RADIUS,
                    other -> !other.getUniqueId().equals(creeper.getUniqueId())).size();
            if (nearby >= POD_MAX_SIZE) {
                event.setCancelled(true);
                return;
            }
            if (nearby >= 1 && ThreadLocalRandom.current().nextDouble() > SECOND_IN_POD_CHANCE) {
                event.setCancelled(true);
                return;
            }
        }

        applyAttributes(creeper);
    }

    // LOWEST so the drop is in place before anything else weighs the hit.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCreeperDamaged(EntityDamageEvent event) {
        if (event.getEntity() instanceof Creeper creeper) {
            applyFuseHealth(creeper);
        }
    }

    /**
     * 1 max health while the fuse is lit, back to a full 60 once it's fully
     * out. Only acts on a change, so a creeper hurt while its fuse is out
     * keeps its damage.
     */
    static void applyFuseHealth(Creeper creeper) {
        AttributeInstance maxHealth = creeper.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null || creeper.isDead()) return;

        boolean fuseLit = creeper.getFuseTicks() > 0 || creeper.isIgnited();
        double wanted = fuseLit ? FUSE_LIT_MAX_HEALTH : MAX_HEALTH;
        if (maxHealth.getBaseValue() == wanted) return;

        maxHealth.setBaseValue(wanted);
        creeper.setHealth(maxHealth.getValue());
    }

    private void applyAttributes(Creeper creeper) {
        AttributeInstance maxHealth = creeper.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(MAX_HEALTH);
            creeper.setHealth(maxHealth.getValue());
        }
        AttributeInstance followRange = creeper.getAttribute(Attribute.FOLLOW_RANGE);
        if (followRange != null) {
            followRange.setBaseValue(FOLLOW_RANGE);
        }
        AttributeInstance stepHeight = creeper.getAttribute(Attribute.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.setBaseValue(STEP_HEIGHT);
        }
        AttributeInstance jumpStrength = creeper.getAttribute(Attribute.JUMP_STRENGTH);
        if (jumpStrength != null) {
            jumpStrength.setBaseValue(jumpStrength.getBaseValue() * JUMP_STRENGTH_MULTIPLIER);
        }
        creeper.setExplosionRadius(EXPLOSION_RADIUS);
    }
}
