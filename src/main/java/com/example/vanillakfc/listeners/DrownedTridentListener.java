package com.example.vanillakfc.listeners;

import org.bukkit.entity.Drowned;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Halves the damage of tridents thrown by Drowned.
 *
 * <p>Only the thrown projectile is affected: the damager has to be a
 * {@link Trident} whose shooter is a {@link Drowned}. Tridents thrown or
 * swung by players are untouched, as is any melee hit from a Drowned.
 *
 * <p>Only the raw hit is halved, before armor and resistance apply, so the
 * final damage taken is roughly - not precisely - half: armor's reduction
 * depends on how hard the hit was.
 */
public final class DrownedTridentListener implements Listener {

    private static final double DAMAGE_MULTIPLIER = 0.5;

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Trident trident && trident.getShooter() instanceof Drowned) {
            event.setDamage(event.getDamage() * DAMAGE_MULTIPLIER);
        }
    }
}
