package com.example.vanillakfc.listeners;

import org.bukkit.entity.Ageable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;

/**
 * Bred baby animals take 3x as long to grow into adults.
 *
 * <p>Vanilla starts a newborn's age counter at a negative number of ticks
 * that counts up to 0 (maturity) one tick at a time; by the time
 * {@link EntityBreedEvent} fires, the child already has that starting age.
 * Multiplying it by 3 here - rather than hardcoding a fixed tick count -
 * stretches the same per-tick aging to take three times as long while
 * staying correct regardless of what vanilla's default baby duration is.
 *
 * <p>Only applies to babies produced by breeding (this event), not
 * naturally spawned wild babies.
 */
public final class BreedingGrowthListener implements Listener {

    private static final int GROWTH_TIME_MULTIPLIER = 3;

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        LivingEntity child = event.getEntity();
        if (!(child instanceof Ageable ageable) || ageable.isAdult()) {
            return;
        }

        ageable.setAge(ageable.getAge() * GROWTH_TIME_MULTIPLIER);
    }
}
