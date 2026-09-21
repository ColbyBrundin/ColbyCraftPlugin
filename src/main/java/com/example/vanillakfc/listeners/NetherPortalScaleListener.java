package com.example.vanillakfc.listeners;

import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Nether portals normally link the Nether and Overworld at an 8:1 ratio (1
 * Nether block = 8 Overworld blocks), via each dimension's own
 * coordinate_scale. This rewrites that link to a flat 1:1 ratio instead - the
 * X/Z an entity stands on in one dimension is the X/Z it arrives at in the
 * other, no rescaling.
 *
 * <p>By the time either portal event fires, vanilla has already computed its
 * own destination - {@link PlayerPortalEvent#getTo()}/
 * {@link EntityPortalEvent#getTo()} both document that it's {@code getFrom()}
 * "scaled for dimension scaling and clamped to be inside the world border" -
 * including picking the correct target world and a height-clamped Y. Only
 * X/Z need correcting here: they're replaced with the entity's original,
 * unscaled X/Z, re-clamped to the target world's own border; the world, Y,
 * and facing vanilla already resolved are left untouched.
 *
 * <p>Only {@link TeleportCause#NETHER_PORTAL} / {@link PortalType#NETHER} are
 * handled - the End doesn't use this scaling in vanilla to begin with
 * (its coordinate_scale already matches the Overworld's), so there's nothing
 * to correct there.
 */
public final class NetherPortalScaleListener implements Listener {

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getCause() != TeleportCause.NETHER_PORTAL) return;

        Location rescaled = rescale(event.getFrom(), event.getTo());
        if (rescaled != null) {
            event.setTo(rescaled);
        }
    }

    @EventHandler
    public void onEntityPortal(EntityPortalEvent event) {
        if (event.getPortalType() != PortalType.NETHER) return;

        Location rescaled = rescale(event.getFrom(), event.getTo());
        if (rescaled != null) {
            event.setTo(rescaled);
        }
    }

    private Location rescale(Location from, Location vanillaTo) {
        if (vanillaTo == null) return null;
        World targetWorld = vanillaTo.getWorld();
        if (targetWorld == null) return null;

        WorldBorder border = targetWorld.getWorldBorder();
        Location center = border.getCenter();
        double half = border.getSize() / 2.0;

        double x = clamp(from.getX(), center.getX() - half, center.getX() + half);
        double z = clamp(from.getZ(), center.getZ() - half, center.getZ() + half);

        Location result = vanillaTo.clone();
        result.setX(x);
        result.setZ(z);
        return result;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
