package com.example.vanillakfc.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;

/**
 * Beds/respawn anchors exploding outside the overworld get replaced with a
 * much smaller explosion instead of the vanilla one.
 */
public final class BedExplosionListener implements Listener {

    // Same power as a ghast fireball (vanilla bed/anchor explosions are power 5.0F).
    private static final float SMALL_EXPLOSION_POWER = 1.0F;

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        // getBlock() reflects the post-explosion state (already air by the
        // time this fires); the original block type is only available via
        // the captured snapshot.
        Material type = event.getExplodedBlockState().getType();
        boolean isBed = Tag.BEDS.isTagged(type);
        boolean isRespawnAnchor = type == Material.RESPAWN_ANCHOR;
        if (!isBed && !isRespawnAnchor) {
            return;
        }

        event.setCancelled(true);
        Block block = event.getBlock();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().createExplosion(center, SMALL_EXPLOSION_POWER, false, true);
    }
}
