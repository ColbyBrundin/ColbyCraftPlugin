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
 * a hit rather than dying to a stray skeleton on the way up, and flies at
 * {@link #HARNESSED_BLOCKS_PER_SECOND} blocks per second instead of vanilla's
 * 3.29 - still no rival to an elytra, but no longer slower than walking.
 *
 * <p>"Theirs" means wearing a harness. A happy ghast has no owner in the way
 * a wolf or a cat does - it isn't {@link org.bukkit.entity.Tameable} at all -
 * and the harness is the only thing in the game that marks one as claimed
 * rather than wild: it is what vanilla itself checks, through {@code
 * isWearingBodyArmor()}, before letting anyone ride. Wild happy ghasts and
 * ghastlings keep vanilla's 20.
 *
 * <p>Both boosts are tied to the harness rather than granted once, so taking
 * the harness off puts the ghast back to vanilla's 20 health and speed, and
 * any health above that ceiling comes down with it. Otherwise a harness could
 * be fitted and immediately removed to keep the health for free. Wild ghasts
 * keeping vanilla's speed also leaves the ones drifting around the Nether
 * moving exactly as they always did.
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

    /** Flat-out ridden speed a harnessed ghast settles at, in blocks per second. */
    private static final double HARNESSED_BLOCKS_PER_SECOND = 6.0;

    // Vanilla's ridden-flight constants, read off HappyGhast/LivingEntity in
    // 26.3. A rider's input is scaled by RIDER_INPUT_SCALE x flying speed,
    // flight then moves at TRAVEL_SPEED_FACTOR x flying speed, and each tick
    // keeps AIR_DRAG of the speed already built up. Flying speed therefore
    // enters the per-tick acceleration twice, which is why the value below is
    // a square root rather than a straight ratio - 6 b/s needs x1.35 on the
    // attribute, not x1.83.
    private static final double RIDER_INPUT_SCALE = 3.9;
    private static final double TRAVEL_SPEED_FACTOR = 5.0 / 3.0;
    private static final double AIR_DRAG = 0.91;
    private static final double TICKS_PER_SECOND = 20.0;

    // Terminal speed per tick is AIR_DRAG / (1 - AIR_DRAG) x the per-tick
    // acceleration; solving that for the attribute gives 0.0676, against
    // vanilla's 0.05 (which is 3.29 b/s by the same formula).
    private static final double HARNESSED_FLYING_SPEED = Math.sqrt(
            (HARNESSED_BLOCKS_PER_SECOND / TICKS_PER_SECOND) * (1.0 - AIR_DRAG) / AIR_DRAG
                    / (RIDER_INPUT_SCALE * TRAVEL_SPEED_FACTOR));

    private final Plugin plugin;
    private final NamespacedKey maxHealthKey;
    private final NamespacedKey flyingSpeedKey;
    private BukkitTask task;

    public HappyGhastListener(Plugin plugin) {
        this.plugin = plugin;
        this.maxHealthKey = new NamespacedKey(plugin, "happy_ghast_max_health");
        this.flyingSpeedKey = new NamespacedKey(plugin, "happy_ghast_flying_speed");
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
                updateFlyingSpeed(ghast);
            }
        }
    }

    private void updateMaxHealth(HappyGhast ghast) {
        AttributeInstance maxHealth = ghast.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) return;

        if (!isHarnessed(ghast)) {
            if (removeBoost(maxHealth, maxHealthKey) && ghast.getHealth() > maxHealth.getValue()) {
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
        removeBoost(maxHealth, maxHealthKey);
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

    private void updateFlyingSpeed(HappyGhast ghast) {
        AttributeInstance flyingSpeed = ghast.getAttribute(Attribute.FLYING_SPEED);
        if (flyingSpeed == null) return;

        if (!isHarnessed(ghast)) {
            removeBoost(flyingSpeed, flyingSpeedKey);
            return;
        }

        if (Math.abs(flyingSpeed.getValue() - HARNESSED_FLYING_SPEED) < EPSILON) return;

        removeBoost(flyingSpeed, flyingSpeedKey);
        flyingSpeed.addModifier(new AttributeModifier(
                flyingSpeedKey,
                HARNESSED_FLYING_SPEED - flyingSpeed.getBaseValue(),
                AttributeModifier.Operation.ADD_NUMBER));
    }

    private static boolean isHarnessed(HappyGhast ghast) {
        EntityEquipment equipment = ghast.getEquipment();
        if (equipment == null) return false;
        return Tag.ITEMS_HARNESSES.isTagged(equipment.getItem(EquipmentSlot.BODY).getType());
    }

    private static boolean removeBoost(AttributeInstance attribute, NamespacedKey key) {
        for (AttributeModifier modifier : attribute.getModifiers()) {
            if (modifier.getKey().equals(key)) {
                attribute.removeModifier(key);
                return true;
            }
        }
        return false;
    }
}
