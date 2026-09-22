package com.example.vanillakfc.listeners;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.HappyGhast;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * A happy ghast someone has made theirs gets {@link #HARNESSED_MAX_HEALTH}
 * health, up from vanilla's 20, so that something you fly around on can take
 * a hit rather than dying to a stray skeleton on the way up.
 *
 * <p>"Theirs" means wearing a harness. A happy ghast has no owner in the way
 * a wolf or a cat does - it isn't {@link org.bukkit.entity.Tameable} at all -
 * and the harness is the only thing in the game that marks one as claimed
 * rather than wild: it is what vanilla itself checks, through {@code
 * isWearingBodyArmor()}, before letting anyone ride. Wild happy ghasts and
 * ghastlings keep vanilla's 20.
 *
 * <p>The boost is tied to the harness rather than granted once, so taking the
 * harness off puts the ghast back to 20 and any health above that ceiling
 * comes down with it. Otherwise a harness could be fitted and immediately
 * removed to keep the health for free.
 *
 * <p>Run off a sweep rather than an equip event because the harness can
 * arrive by more routes than a player fitting one - a dispenser, a command,
 * or simply loading a ghast that was already harnessed when the chunk was
 * saved - and one sweep covers them all identically. Twice a second is well
 * inside what anyone notices on a health bar, and happy ghasts are far too
 * rare for the scan to cost anything.
 */
public final class HappyGhastListener implements Listener {

    private static final double HARNESSED_MAX_HEALTH = 100.0;
    private static final long POLL_INTERVAL_TICKS = 10L;
    private static final double EPSILON = 1.0e-6;

    private final Plugin plugin;
    private final NamespacedKey maxHealthKey;
    private BukkitTask task;

    public HappyGhastListener(Plugin plugin) {
        this.plugin = plugin;
        this.maxHealthKey = new NamespacedKey(plugin, "happy_ghast_max_health");
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
        for (World world : Bukkit.getWorlds()) {
            for (HappyGhast ghast : world.getEntitiesByClass(HappyGhast.class)) {
                updateMaxHealth(ghast);
            }
        }
    }

    private void updateMaxHealth(HappyGhast ghast) {
        AttributeInstance maxHealth = ghast.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) return;

        if (!isHarnessed(ghast)) {
            if (removeBoost(maxHealth) && ghast.getHealth() > maxHealth.getValue()) {
                // Health sitting above the ceiling that just came back down.
                ghast.setHealth(maxHealth.getValue());
            }
            return;
        }

        // Tested on the resulting value rather than just on whether the
        // modifier is there, so a boost stored against a different base - were
        // vanilla ever to retune a happy ghast's health - is recomputed rather
        // than left sitting at the wrong total.
        if (Math.abs(maxHealth.getValue() - HARNESSED_MAX_HEALTH) < EPSILON) return;

        // Raising the ceiling doesn't fill the bar, so a ghast that was at full
        // would be left on a fifth of its new health. Topped up only if it was
        // full to begin with - a wounded one stays wounded.
        boolean wasFull = ghast.getHealth() >= maxHealth.getValue();
        removeBoost(maxHealth);
        // Derived from the base value rather than hard-coded as "+80" so the
        // total lands on exactly HARNESSED_MAX_HEALTH whatever vanilla's base
        // happens to be.
        maxHealth.addModifier(new AttributeModifier(
                maxHealthKey,
                HARNESSED_MAX_HEALTH - maxHealth.getBaseValue(),
                AttributeModifier.Operation.ADD_NUMBER));
        if (wasFull) {
            ghast.setHealth(maxHealth.getValue());
        }
    }

    private static boolean isHarnessed(HappyGhast ghast) {
        EntityEquipment equipment = ghast.getEquipment();
        if (equipment == null) return false;
        return Tag.ITEMS_HARNESSES.isTagged(equipment.getItem(EquipmentSlot.BODY).getType());
    }

    private boolean removeBoost(AttributeInstance maxHealth) {
        for (AttributeModifier modifier : maxHealth.getModifiers()) {
            if (modifier.getKey().equals(maxHealthKey)) {
                maxHealth.removeModifier(maxHealthKey);
                return true;
            }
        }
        return false;
    }
}
