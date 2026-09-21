package com.example.vanillakfc.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Vanilla only converts concrete powder to concrete when it's a placed
 * block touching water - the item form (a dropped or thrown stack) has no
 * such mechanic. This extends the same conversion to item entities: once a
 * concrete powder stack has been continuously sitting in water for
 * {@link #SUBMERGED_MILLIS_REQUIRED}, its stack is swapped in place for the
 * matching solid-concrete color, same amount, regardless of stack size.
 */
public final class ConcretePowderListener {

    private static final long SUBMERGED_MILLIS_REQUIRED = 3000L;
    private static final long POLL_INTERVAL_TICKS = 5L;

    private final Plugin plugin;
    private final Map<UUID, Long> submergedSince = new HashMap<>();
    private BukkitTask task;

    public ConcretePowderListener(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, POLL_INTERVAL_TICKS, POLL_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        Set<UUID> stillInWater = new HashSet<>();

        for (World world : Bukkit.getWorlds()) {
            for (Item item : world.getEntitiesByClass(Item.class)) {
                ItemStack stack = item.getItemStack();
                Material concrete = concreteFor(stack.getType());
                if (concrete == null) {
                    continue;
                }

                UUID id = item.getUniqueId();
                if (!item.isInWater()) {
                    submergedSince.remove(id); // leaving water resets the wait - it has to be continuous
                    continue;
                }

                stillInWater.add(id);
                long submergedAt = submergedSince.computeIfAbsent(id, ignored -> now);
                if (now - submergedAt >= SUBMERGED_MILLIS_REQUIRED) {
                    item.setItemStack(new ItemStack(concrete, stack.getAmount()));
                    submergedSince.remove(id);
                }
            }
        }

        submergedSince.keySet().retainAll(stillInWater);
    }

    // "WHITE_CONCRETE_POWDER" -> "WHITE_CONCRETE"; null for anything that
    // isn't a concrete powder variant.
    private static Material concreteFor(Material powder) {
        String name = powder.name();
        if (!name.endsWith("_CONCRETE_POWDER")) {
            return null;
        }
        return Material.matchMaterial(name.substring(0, name.length() - "_POWDER".length()));
    }
}
