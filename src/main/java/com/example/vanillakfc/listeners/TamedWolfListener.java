package com.example.vanillakfc.listeners;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Tamed wolves ("dogs") are considerably tougher and harder-hitting than
 * vanilla: max health raised from 40 to {@link #TAMED_MAX_HEALTH}, and melee
 * damage increased by {@link #ATTACK_DAMAGE_MULTIPLIER}. Applied on taming
 * ({@link EntityTameEvent}, deferred a tick so it lands after whatever
 * vanilla's own post-tame processing does - applying it synchronously in the
 * same tick the event fires was observed getting silently reverted), and
 * reinforced by a periodic scan over every tamed wolf in the world -
 * covering puppies bred from two already-tamed wolves (which spawn pre-tamed
 * with no taming event to hook) and wolves that were already tamed before
 * this listener was ever loaded, e.g. from an earlier session.
 *
 * <p>Max health is corrected on every scan pass regardless of whether this
 * wolf has been touched before - setting the same base value repeatedly is
 * harmless, and doing it unconditionally means it can never get stuck at the
 * old value if some earlier attempt silently didn't take. Attack damage is
 * the one buff that isn't safe to repeat (it's a relative multiply, not an
 * absolute value), so it alone is gated behind a persistent tag marking a
 * wolf as already-multiplied. Wild wolves are entirely unaffected.
 */
public final class TamedWolfListener implements Listener {

    private static final double TAMED_MAX_HEALTH = 100.0;
    private static final double ATTACK_DAMAGE_MULTIPLIER = 1.5;
    private static final long SCAN_INTERVAL_TICKS = 20L;

    private final Plugin plugin;
    private final NamespacedKey buffedKey;
    private BukkitTask task;

    public TamedWolfListener(Plugin plugin) {
        this.plugin = plugin;
        this.buffedKey = new NamespacedKey(plugin, "tamed_wolf_buffed");
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::scan, SCAN_INTERVAL_TICKS, SCAN_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @EventHandler
    public void onTame(EntityTameEvent event) {
        if (event.getEntityType() != EntityType.WOLF) return;
        if (!(event.getEntity() instanceof Wolf wolf)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (wolf.isValid()) {
                applyBuffs(wolf);
            }
        });
    }

    private void scan() {
        for (World world : Bukkit.getWorlds()) {
            for (Wolf wolf : world.getEntitiesByClass(Wolf.class)) {
                if (wolf.isTamed()) {
                    applyBuffs(wolf);
                }
            }
        }
    }

    private void applyBuffs(Wolf wolf) {
        AttributeInstance maxHealth = wolf.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null && maxHealth.getBaseValue() != TAMED_MAX_HEALTH) {
            maxHealth.setBaseValue(TAMED_MAX_HEALTH);
            wolf.setHealth(TAMED_MAX_HEALTH);
        }

        if (wolf.getPersistentDataContainer().has(buffedKey, PersistentDataType.BYTE)) {
            return; // attack damage already multiplied - repeating it would compound
        }

        AttributeInstance attackDamage = wolf.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attackDamage != null) {
            attackDamage.setBaseValue(attackDamage.getBaseValue() * ATTACK_DAMAGE_MULTIPLIER);
        }

        wolf.getPersistentDataContainer().set(buffedKey, PersistentDataType.BYTE, (byte) 1);
    }
}
