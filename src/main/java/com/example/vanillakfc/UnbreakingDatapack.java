package com.example.vanillakfc;

import io.papermc.paper.datapack.Datapack;
import org.bukkit.Bukkit;

/**
 * Tracks whether the bundled datapack that strips vanilla's Unbreaking
 * durability reduction is actually in force.
 *
 * <p>{@link com.example.vanillakfc.listeners.UnbreakingListener} needs to know,
 * because the two states call for opposite handling and getting it backwards is
 * not subtle. With the pack on, vanilla reduces nothing, the durability event
 * fires on every use, and the listener applies the whole curve itself. With it
 * off, vanilla has already applied its own reduction, and applying the curve
 * again on top would stack the two - an Unbreaking III tool would last about
 * four times longer than intended rather than twice.
 *
 * <p>The name is captured at discovery rather than assumed, since it is the
 * server that decides what a discovered pack ends up called. Nothing recorded
 * means discovery never ran or failed, which reads as "off" - the fallback is
 * the old scaling behaviour, which is correct on its own terms rather than
 * broken.
 *
 * <p>{@link #isActive()} re-reads the enabled flag every time rather than
 * caching it, so {@code /datapack disable} takes effect immediately instead of
 * quietly leaving the listener double-counting until a restart.
 */
public final class UnbreakingDatapack {

    /** The id the bootstrap registers the bundled pack under. */
    public static final String PACK_ID = "vanillakfc";

    private static volatile String discoveredName;
    private static volatile Datapack cachedPack;

    static void discovered(String name) {
        discoveredName = name;
        cachedPack = null;
    }

    public static boolean isActive() {
        String name = discoveredName;
        if (name == null) {
            return false;
        }
        Datapack pack = cachedPack;
        if (pack == null) {
            pack = Bukkit.getDatapackManager().getPack(name);
            if (pack == null) {
                return false;
            }
            cachedPack = pack;
        }
        return pack.isEnabled();
    }

    /** For the startup log line, so which mode is running is never a guess. */
    public static String describe() {
        if (discoveredName == null) {
            return "not discovered - Unbreaking falls back to scaling vanilla's own roll";
        }
        return isActive()
                ? "active as '" + discoveredName + "' - Unbreaking applies its curve directly"
                : "discovered as '" + discoveredName + "' but disabled - Unbreaking falls back to scaling";
    }

    private UnbreakingDatapack() {
    }
}
