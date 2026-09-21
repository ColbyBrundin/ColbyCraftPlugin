package com.example.vanillakfc.debug;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Debug overlay: draws a red X on every standable spot near the player that is
 * dark enough for hostile mobs to spawn. Toggled per player with
 * {@code /lightdebug}.
 *
 * <p>A server can't draw on a client's screen the way F3+G or a light-overlay
 * mod does, but it can send particles to one specific player, which is what
 * this uses - so the markers are visible only to whoever turned them on, with
 * no client mod and no effect on anyone else.
 *
 * <p>Darkness is judged by <em>block</em> light ({@link Block#getLightFromBlocks()}),
 * not the combined level {@link Block#getLightLevel()} reports. Since 1.18 a
 * hostile mob needs block light 0 to spawn and sky light doesn't stop it, so
 * block light is what spawn-proofing actually turns on: a field lit only by
 * daylight reads 15 on the combined scale while still spawning mobs at night.
 * Pass {@code total} to switch to the combined level instead.
 *
 * <p>Only spots something could actually stand in are considered - a passable,
 * non-liquid block resting on a solid one. Without that, every solid block
 * underground qualifies as dark and the whole view fills with markers.
 */
public final class LightLevelDebug implements CommandExecutor, Listener {

    private static final int DEFAULT_RADIUS = 12;
    private static final int MAX_RADIUS = 32;
    private static final int VERTICAL_RANGE = 4;
    private static final long REFRESH_TICKS = 10L;
    /** Bounds the particle count when someone points this at a large dark cave. */
    private static final int MAX_MARKERS_PER_PLAYER = 400;

    private final Plugin plugin;
    private final Map<UUID, Settings> enabled = new HashMap<>();
    private BukkitTask task;

    private record Settings(int radius, boolean useTotalLight) {
    }

    public LightLevelDebug(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::render, REFRESH_TICKS, REFRESH_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        enabled.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        enabled.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly a player can toggle the light overlay.");
            return true;
        }

        if (enabled.remove(player.getUniqueId()) != null && args.length == 0) {
            player.sendMessage("§eLight overlay off.");
            return true;
        }

        int radius = DEFAULT_RADIUS;
        boolean useTotalLight = false;
        for (String arg : args) {
            if (arg.equalsIgnoreCase("total")) {
                useTotalLight = true;
            } else if (arg.equalsIgnoreCase("block")) {
                useTotalLight = false;
            } else {
                try {
                    radius = Math.clamp(Integer.parseInt(arg), 1, MAX_RADIUS);
                } catch (NumberFormatException e) {
                    player.sendMessage("§cUsage: /" + label + " [radius] [block|total]");
                    return true;
                }
            }
        }

        enabled.put(player.getUniqueId(), new Settings(radius, useTotalLight));
        player.sendMessage("§eLight overlay on - marking spots with "
                + (useTotalLight ? "total light" : "block light") + " 0 within " + radius + " blocks.");
        return true;
    }

    private void render() {
        for (Map.Entry<UUID, Settings> entry : enabled.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }
            markDarkSpots(player, entry.getValue());
        }
    }

    private void markDarkSpots(Player player, Settings settings) {
        World world = player.getWorld();
        Location origin = player.getLocation();
        int radius = settings.radius();
        int drawn = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -VERTICAL_RANGE; dy <= VERTICAL_RANGE; dy++) {
                    int x = origin.getBlockX() + dx;
                    int y = origin.getBlockY() + dy;
                    int z = origin.getBlockZ() + dz;
                    // Skip unloaded chunks rather than pulling them in just to
                    // read a light level.
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    if (!isStandableSpot(block) || lightOf(block, settings) != 0) {
                        continue;
                    }
                    drawX(player, block);
                    if (++drawn >= MAX_MARKERS_PER_PLAYER) {
                        return;
                    }
                }
            }
        }
    }

    private static boolean isStandableSpot(Block block) {
        if (!block.isPassable() || block.isLiquid()) {
            return false;
        }
        Block floor = block.getRelative(BlockFace.DOWN);
        return floor.getType().isSolid();
    }

    private static int lightOf(Block block, Settings settings) {
        return settings.useTotalLight() ? block.getLightLevel() : block.getLightFromBlocks();
    }

    // Five points across the top face of the block below: the four corners of
    // an X plus its crossing point.
    private static void drawX(Player player, Block block) {
        Particle.DustOptions dust = new Particle.DustOptions(Color.RED, 0.7f);
        double y = block.getY() + 0.03;
        double[][] offsets = {{0.2, 0.2}, {0.8, 0.8}, {0.2, 0.8}, {0.8, 0.2}, {0.5, 0.5}};
        for (double[] offset : offsets) {
            player.spawnParticle(Particle.DUST,
                    block.getX() + offset[0], y, block.getZ() + offset[1],
                    1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }
}
