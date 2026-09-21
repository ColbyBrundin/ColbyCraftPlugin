package com.example.vanillakfc.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.inventory.ItemStack;

public final class MaceDamageListener implements Listener {

    private static final double MAX_BASE_DAMAGE = 40.0;

    // Cap the Mace's raw attack damage (base hit + crit + fall-damage bonus)
    // before armor/resistance modifiers are applied on top. DamageModifier is
    // deprecated but remains the only API that exposes pre-mitigation damage.
    @SuppressWarnings("deprecation")
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;

        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (weapon.getType() != Material.MACE) return;

        if (event.getDamage(DamageModifier.BASE) > MAX_BASE_DAMAGE) {
            event.setDamage(DamageModifier.BASE, MAX_BASE_DAMAGE);
        }
    }
}
