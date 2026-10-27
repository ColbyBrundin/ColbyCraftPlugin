package com.example.vanillakfc.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A hit worth more than {@link #BREAK_THRESHOLD} damage knocks a player's
 * raised shield down, the way an axe does: the hit itself is still blocked,
 * then the shield goes on a {@link #DISABLE_TICKS}-tick cooldown with
 * vanilla's shield-break sound.
 *
 * <p>"Worth" is the hit before the shield took any of it - the event's base
 * damage, which already includes difficulty scaling - since a blocked hit's
 * final damage is near zero by definition. Any blocked hit counts: melee,
 * projectiles and explosions alike.
 *
 * <p>Applied a tick after the hit rather than inside the event, so vanilla
 * finishes blocking it first (shield durability, the block sound) instead of
 * finding the shield already lowered part way through.
 */
public final class ShieldBreakListener implements Listener {

    private static final double BREAK_THRESHOLD = 12.0;
    // An axe's disable time on a vanilla shield: 5 seconds.
    private static final int DISABLE_TICKS = 100;

    private final Plugin plugin;

    public ShieldBreakListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("deprecation") // DamageModifier: still the only way to tell a hit was blocked, and how big it was
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !player.isBlocking()) return;
        if (!event.isApplicable(DamageModifier.BLOCKING) || event.getDamage(DamageModifier.BLOCKING) >= 0) return;
        if (event.getDamage(DamageModifier.BASE) <= BREAK_THRESHOLD) return;

        ItemStack shield = player.getActiveItem();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.isDead()) return;
            // Already knocked down this tick, or a later hit beat us to it.
            if (player.hasCooldown(shield)) return;
            player.setCooldown(shield, DISABLE_TICKS);
            player.clearActiveItem();
            // Vanilla's default shield disable sound, at its own random pitch.
            float pitch = 0.8F + ThreadLocalRandom.current().nextFloat() * 0.4F;
            player.getWorld().playSound(player, Sound.ITEM_SHIELD_BREAK, SoundCategory.PLAYERS, 1.0F, pitch);
        });
    }
}
