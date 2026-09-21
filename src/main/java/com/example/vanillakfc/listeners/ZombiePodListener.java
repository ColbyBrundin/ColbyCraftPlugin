package com.example.vanillakfc.listeners;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Vanilla natural spawning fires one {@link CreatureSpawnEvent} per pack
 * member, so spawning exactly one clone per natural zombie spawn doubles
 * pack size without needing to detect pack grouping explicitly. The clone
 * spawns with {@link CreatureSpawnEvent.SpawnReason#CUSTOM} specifically so
 * this listener's own natural-spawn check prevents the clone from being
 * doubled again.
 */
public final class ZombiePodListener implements Listener {

    @EventHandler
    public void onZombieSpawn(CreatureSpawnEvent event) {
        if (event.isCancelled()) return;
        if (event.getEntityType() != EntityType.ZOMBIE) return;
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;

        Zombie original = (Zombie) event.getEntity();
        original.getWorld().spawn(original.getLocation(), Zombie.class,
                CreatureSpawnEvent.SpawnReason.CUSTOM, true, null);
    }
}
