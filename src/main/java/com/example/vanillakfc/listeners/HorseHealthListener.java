package com.example.vanillakfc.listeners;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Horses have 40-60 max health instead of vanilla's 15-30.
 *
 * <p>Both of vanilla's own rules are kept, just moved onto the new range:
 *
 * <ul>
 * <li><b>New horses</b> (wild, spawn egg, command) roll 40 + 0-10 + 0-10 -
 * the same shape as vanilla's 15 + 0-7 + 0-8, so most land near the middle
 * (50) and the extremes are rare.
 * <li><b>Foals</b> take their parents' average plus a random spread, as in
 * vanilla, kept inside 40-60. Vanilla works this out inside 15-30 before
 * any event fires, which would cap every foal at 30, so it's redone here
 * from the parents.
 * </ul>
 *
 * <p><b>Horses from before this.</b> Any horse still under 40 is one rolled
 * on vanilla's range, and is moved onto the new one in proportion rather
 * than re-rolled - 15 becomes 40, 30 becomes 60, and everything between
 * keeps its place - so a horse bred to be good stays good. It's healed to
 * its new full health. Done as horses load ({@link #onEntitiesLoad}) and for
 * everything already loaded at startup ({@link #convertLoaded}); a converted
 * horse is at 40 or more, so nothing needs marking and nothing is ever
 * converted twice.
 *
 * <p>Only horses - donkeys, mules, and skeleton and zombie horses are
 * unchanged.
 */
public final class HorseHealthListener implements Listener {

    private static final double MIN_HEALTH = 40.0;
    private static final double MAX_HEALTH = 60.0;
    private static final double VANILLA_MIN_HEALTH = 15.0;
    private static final double VANILLA_MAX_HEALTH = 30.0;

    private final Logger logger;

    public HorseHealthListener(Logger logger) {
        this.logger = logger;
    }

    /** Converts every horse already loaded when the plugin starts. */
    public void convertLoaded() {
        int converted = 0;
        for (World world : Bukkit.getWorlds()) {
            converted += convert(world.getEntitiesByClass(Horse.class));
        }
        logIfConverted(converted);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        logIfConverted(convert(event.getEntities()));
    }

    private int convert(Collection<? extends Entity> entities) {
        int converted = 0;
        for (Entity entity : entities) {
            if (!(entity instanceof Horse horse) || horse.isDead()) continue;
            double old = baseMaxHealth(horse);
            if (old >= MIN_HEALTH) continue;
            setMaxHealth(horse, rescaled(old));
            converted++;
        }
        return converted;
    }

    // A vanilla roll's place in 15-30, carried over to the same place in
    // 40-60. Anything outside vanilla's range (a command-made horse) is
    // treated as the nearest end of it.
    private static double rescaled(double vanillaHealth) {
        double clamped = Math.clamp(vanillaHealth, VANILLA_MIN_HEALTH, VANILLA_MAX_HEALTH);
        double place = (clamped - VANILLA_MIN_HEALTH) / (VANILLA_MAX_HEALTH - VANILLA_MIN_HEALTH);
        return MIN_HEALTH + place * (MAX_HEALTH - MIN_HEALTH);
    }

    private void logIfConverted(int converted) {
        if (converted > 0) {
            logger.info("Horse health: moved " + converted + " existing horse(s) onto the 40-60 range.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Horse horse)) return;
        // Foals are handled by onBreed, from their parents. CUSTOM is another
        // plugin's own spawn, whose stats are its business.
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.BREEDING || reason == CreatureSpawnEvent.SpawnReason.CUSTOM) return;

        ThreadLocalRandom random = ThreadLocalRandom.current();
        setMaxHealth(horse, MIN_HEALTH + random.nextInt(11) + random.nextInt(11));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (!(event.getEntity() instanceof Horse foal)) return;
        if (!(event.getMother() instanceof Horse mother) || !(event.getFather() instanceof Horse father)) return;
        setMaxHealth(foal, offspringHealth(baseMaxHealth(mother), baseMaxHealth(father)));
    }

    // Vanilla's AbstractHorse#createOffspringAttribute, on this range: the
    // parents' average, spread by their difference plus 15% of the range
    // either way (an average of three rolls, so it leans to the middle), and
    // anything past an end reflected back inside it.
    private static double offspringHealth(double parentA, double parentB) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double a = Math.clamp(parentA, MIN_HEALTH, MAX_HEALTH);
        double b = Math.clamp(parentB, MIN_HEALTH, MAX_HEALTH);
        double margin = 0.15 * (MAX_HEALTH - MIN_HEALTH);
        double spread = Math.abs(a - b) + margin * 2.0;
        double average = (a + b) / 2.0;
        double roll = (random.nextDouble() + random.nextDouble() + random.nextDouble()) / 3.0 - 0.5;
        double value = average + spread * roll;
        if (value > MAX_HEALTH) return MAX_HEALTH - (value - MAX_HEALTH);
        if (value < MIN_HEALTH) return MIN_HEALTH + (MIN_HEALTH - value);
        return value;
    }

    private static double baseMaxHealth(LivingEntity entity) {
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? MIN_HEALTH : attribute.getBaseValue();
    }

    private static void setMaxHealth(LivingEntity entity, double maxHealth) {
        AttributeInstance attribute = entity.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) return;
        attribute.setBaseValue(maxHealth);
        entity.setHealth(attribute.getValue());
    }
}
