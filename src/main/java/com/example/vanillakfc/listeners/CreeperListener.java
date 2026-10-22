package com.example.vanillakfc.listeners;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Creepers have 12 max health (vanilla 20), see 32 blocks out and step/jump
 * over terrain much more readily (chase speed is handled dynamically in
 * {@link CreeperBehaviorTask}, since it only applies while actively closing
 * in on a player). Natural spawns are throttled and capped to pods of 1-2.
 */
public final class CreeperListener implements Listener {

    private static final double MAX_HEALTH = 12.0;
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

    private void applyAttributes(Creeper creeper) {
        AttributeInstance maxHealth = creeper.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(MAX_HEALTH);
            creeper.setHealth(Math.min(creeper.getHealth(), maxHealth.getValue()));
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
