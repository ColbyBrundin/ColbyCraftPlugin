package com.example.vanillakfc.listeners;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hostile mobs ({@link Enemy} - Zombies, Skeletons, Creepers, Spiders,
 * Endermen, Slimes, Ghasts, Shulkers, etc.) and untamed passive animals
 * ({@link Animals} - cows, sheep, pigs, chickens, wild horses, etc.) show
 * their current/max health as a floating label, using the same
 * invisible-marker-armor-stand technique {@link PetHealthDisplayListener}
 * uses for pets - except here the label only exists while a player is
 * actually looking directly at that mob's model, appearing and disappearing
 * with their aim rather than staying up permanently the way a pet's does.
 * "Looking at" requires both a precise crosshair hit on the mob's hitbox (so
 * standing near one without aiming at it doesn't light it up) and an
 * unobstructed line of sight (so it can't be seen through a wall the
 * crosshair ray happens to pass behind).
 *
 * <p>Look-triggered rather than always-visible matters far more for passive
 * animals than it did for hostiles: a player's render distance routinely
 * holds entire herds and full farm pens, and a permanent label on every one
 * would mean an armor stand per animal, all of them re-teleported every
 * tick.
 *
 * <p>Tamed animals are deliberately excluded - {@link PetHealthDisplayListener}
 * already gives those a permanent label, and adding a second one here would
 * stack a duplicate at the same height above the pet.
 */
public final class MobHealthDisplayListener {

    private static final long POLL_INTERVAL_TICKS = 1L;
    private static final double LOOK_RANGE = 40.0;
    private static final double LABEL_HEIGHT_ABOVE_MOB = 0.4;

    private final Plugin plugin;
    private final NamespacedKey ownerKey;
    private final Map<UUID, ArmorStand> companions = new HashMap<>();
    private final Map<UUID, String> lastLabelText = new HashMap<>();
    private BukkitTask task;

    public MobHealthDisplayListener(Plugin plugin) {
        this.plugin = plugin;
        // Deliberately still "hostile_..." despite this listener having grown
        // to cover passive animals too: the key is what identifies an already
        // spawned companion stand, so changing it would strand every label
        // left in the world by a previous run instead of re-adopting it.
        this.ownerKey = new NamespacedKey(plugin, "hostile_health_label_owner");
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
        Set<UUID> lookedAt = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            LivingEntity target = findLookedAtMob(player);
            if (target == null) continue;
            lookedAt.add(target.getUniqueId());
            updateLabel(target);
        }

        for (UUID orphaned : new HashSet<>(companions.keySet())) {
            if (!lookedAt.contains(orphaned)) {
                removeCompanion(orphaned);
            }
        }
        lastLabelText.keySet().retainAll(lookedAt);
    }

    private LivingEntity findLookedAtMob(Player player) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(), player.getEyeLocation().getDirection(), LOOK_RANGE,
                entity -> isDisplayTarget(entity) && entity.isValid());
        if (result == null) return null;

        Entity hit = result.getHitEntity();
        if (!(hit instanceof LivingEntity target)) return null;
        return player.hasLineOfSight(target) ? target : null;
    }

    private static boolean isDisplayTarget(Entity entity) {
        if (entity instanceof Enemy) {
            return true;
        }
        return entity instanceof Animals
                && !(entity instanceof Tameable tameable && tameable.isTamed());
    }

    private void updateLabel(LivingEntity mob) {
        ArmorStand label = getOrCreateCompanion(mob);
        label.teleport(mob.getLocation().add(0, mob.getHeight() + LABEL_HEIGHT_ABOVE_MOB, 0));

        String text = healthText(mob);
        if (!text.equals(lastLabelText.get(mob.getUniqueId()))) {
            label.customName(Component.text(text));
            lastLabelText.put(mob.getUniqueId(), text);
        }
    }

    private ArmorStand getOrCreateCompanion(LivingEntity mob) {
        ArmorStand cached = companions.get(mob.getUniqueId());
        if (cached != null && cached.isValid()) {
            return cached;
        }

        // Not cached - either the label was just triggered for the first
        // time, or a server restart lost track of an existing one. Check
        // for an existing tagged companion nearby before spawning a new
        // one, so a restart mid-look doesn't leave a duplicate behind.
        for (Entity nearby : mob.getNearbyEntities(2, 2, 2)) {
            if (nearby instanceof ArmorStand stand && isCompanionFor(stand, mob)) {
                companions.put(mob.getUniqueId(), stand);
                return stand;
            }
        }

        ArmorStand created = mob.getWorld().spawn(mob.getLocation(), ArmorStand.class,
                CreatureSpawnEvent.SpawnReason.CUSTOM, false, stand -> {
                    stand.setInvisible(true);
                    stand.setMarker(true);
                    stand.setGravity(false);
                    stand.setInvulnerable(true);
                    stand.setCustomNameVisible(true);
                    stand.setPersistent(true);
                    stand.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, mob.getUniqueId().toString());
                });
        companions.put(mob.getUniqueId(), created);
        return created;
    }

    private boolean isCompanionFor(ArmorStand stand, LivingEntity mob) {
        String owner = stand.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return mob.getUniqueId().toString().equals(owner);
    }

    private void removeCompanion(UUID mobId) {
        ArmorStand stand = companions.remove(mobId);
        if (stand != null && stand.isValid()) {
            stand.remove();
        }
        lastLabelText.remove(mobId);
    }

    private String healthText(LivingEntity mob) {
        int current = (int) Math.ceil(mob.getHealth());
        AttributeInstance maxHealthAttribute = mob.getAttribute(Attribute.MAX_HEALTH);
        int max = maxHealthAttribute == null ? current : (int) Math.round(maxHealthAttribute.getValue());
        return current + "/" + max;
    }
}
