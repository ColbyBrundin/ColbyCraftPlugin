package com.example.vanillakfc.listeners;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;

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
        ItemStack addition = event.getInventory().getItem(1);
        if (base != null && addition != null && sameEnchantsAndDamage(base, meta)) {
            // Mending was all the second item had to give, so with it removed
            // the anvil would take that item and the levels for nothing.
            event.setResult(null);
        } else {
            event.setResult(result);
        }
    }

    // Deliberately not ItemStack#isSimilar: the anvil has already raised the
    // result's repair cost, so the two never compare equal, and a rename done
    // alongside shouldn't make a wasted book look like it did something.
    // Mending on the base is ignored, as the result has just had it removed.
    private static boolean sameEnchantsAndDamage(ItemStack base, ItemMeta resultMeta) {
        ItemMeta baseMeta = base.getItemMeta();
        if (!(baseMeta instanceof Damageable baseDamage) || !(resultMeta instanceof Damageable resultDamage)) return false;
        if (baseDamage.getDamage() != resultDamage.getDamage()) return false;

        Map<Enchantment, Integer> baseEnchants = new HashMap<>(baseMeta.getEnchants());
        baseEnchants.remove(Enchantment.MENDING);
        return baseEnchants.equals(resultMeta.getEnchants());
    }

    // Defense-in-depth: Mending can't normally roll at the enchanting table,
    // but if it's ever enabled (datapack/command), strip it there too.
    @EventHandler
    public void onEnchantItem(EnchantItemEvent event) {
        if (event.getItem().getType() != Material.ELYTRA) return;
        event.getEnchantsToAdd().remove(Enchantment.MENDING);
    }
}
