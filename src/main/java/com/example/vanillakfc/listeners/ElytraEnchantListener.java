package com.example.vanillakfc.listeners;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class ElytraEnchantListener implements Listener {

    // Mending is a treasure enchantment, so the anvil (combining an Elytra
    // with a Mending book, or with another Mending Elytra) is the only
    // realistic vanilla way it could end up on an Elytra.
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.getType() != Material.ELYTRA) return;

        ItemMeta meta = result.getItemMeta();
        if (meta == null || !meta.hasEnchant(Enchantment.MENDING)) return;

        meta.removeEnchant(Enchantment.MENDING);
        result.setItemMeta(meta);

        ItemStack base = event.getInventory().getItem(0);
        if (base != null && base.isSimilar(result)) {
            // Removing Mending left nothing else for this anvil operation to do.
            event.setResult(null);
        } else {
            event.setResult(result);
        }
    }

    // Defense-in-depth: Mending can't normally roll at the enchanting table,
    // but if it's ever enabled (datapack/command), strip it there too.
    @EventHandler
    public void onEnchantItem(EnchantItemEvent event) {
        if (event.getItem().getType() != Material.ELYTRA) return;
        event.getEnchantsToAdd().remove(Enchantment.MENDING);
    }
}
