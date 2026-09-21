package com.example.vanillakfc.listeners;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The nether's bedrock roof is generated from the noise settings' own top
 * boundary (min_y 0 + height 128) down 6 blocks, so Y=127 is the highest a
 * roof block can ever be - matches the dimension's logical_height of 128.
 * Anything at Y=128 or above is unreachable without clipping through
 * bedrock, so it's treated like vanilla's own below-world void: same
 * damage type ({@code minecraft:out_of_world}, which bypasses invulnerability
 * - including Creative/Spectator and totems - per its
 * bypasses_invulnerability tag), same 4-damage/10-tick cadence as
 * paper-world-defaults.yml's void-damage-amount.
 */
public final class NetherRoofVoidDamageListener {

    private static final double ROOF_TOP_Y = 128.0;
    private static final double DAMAGE_PER_TICK = 4.0;

    private final Plugin plugin;
    private BukkitTask task;

    public NetherRoofVoidDamageListener(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        DamageSource source = DamageSource.builder(DamageType.OUT_OF_WORLD).build();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getEnvironment() != World.Environment.NETHER) continue;
            if (player.getLocation().getY() < ROOF_TOP_Y) continue;
            player.damage(DAMAGE_PER_TICK, source);
        }
    }
}
