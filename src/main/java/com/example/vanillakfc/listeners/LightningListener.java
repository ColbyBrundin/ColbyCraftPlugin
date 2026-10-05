package com.example.vanillakfc.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Vanilla rolls a 1-in-100,000 chance per loaded chunk per tick for a
 * lightning strike during a thunderstorm - that roll lives entirely inside
 * NMS with no exposed API to retune, so this runs an equivalent per-tick,
 * per-chunk roll of its own at the same 1-in-100,000 rate. Vanilla's own
 * roll still fires independently alongside this one and can't be
 * suppressed without NMS access, so the two combine into an effective rate
 * of roughly double vanilla's normal frequency - the intended result,
 * achieved by matching vanilla's rate exactly rather than picking a custom
 * denominator to hit 2x directly.
 */
public final class LightningListener {

    private static final int STRIKE_CHANCE_DENOMINATOR = 100_000;

    private final Plugin plugin;
    private BukkitTask task;

    public LightningListener(Plugin plugin) {
        this.plugin = plugin;
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
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (World world : Bukkit.getWorlds()) {
            // isThundering() and hasStorm() are separate, independently
            // settable flags - a genuine thunderstorm always has both in
            // vanilla's own natural cycle, but nothing guarantees they stay
            // in sync (a command, another plugin, or a weather-transition
            // edge case could desync them). Requiring both closes off any
            // way this could fire - and produce its very audible, far-
            // carrying thunder sound - without an actual, currently-active
            // storm overhead.
            if (!world.isThundering() || !world.hasStorm()) {
                continue;
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                if (random.nextInt(STRIKE_CHANCE_DENOMINATOR) != 0) {
                    continue;
                }
                int x = (chunk.getX() << 4) + random.nextInt(16);
                int z = (chunk.getZ() << 4) + random.nextInt(16);
                // World#findLightningTarget hardcodes isChanneling=true in
                // its NMS call, which is the trident-channeling path: it
                // hunts for a nearby *living entity* and returns null if
                // none is found, rather than falling back to the ground -
                // that's why it failed almost every roll here. Vanilla's own
                // natural weather lightning never goes through that method
                // at all, so this strikes the computed ground position
                // directly instead.
                int groundY = world.getHighestBlockAt(x, z).getY() + 1;
                Location target = new Location(world, x + 0.5, groundY, z + 0.5);
                // Striking the ground directly also skipped vanilla's
                // lightning rod redirect, so these strikes never hit a rod -
                // half of all storm lightning ignored rods entirely. Same
                // rod search vanilla's own roll does first (the nearest rod
                // within 128 blocks), and a rod only activates when the
                // block above it is the one struck.
                Location rod = world.findLightningRod(target);
                if (rod != null) {
                    target = rod.add(0.5, 1, 0.5);
                }
                world.strikeLightning(target);
            }
        }
    }
}
