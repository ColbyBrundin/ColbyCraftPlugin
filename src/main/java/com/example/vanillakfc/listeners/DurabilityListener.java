package com.example.vanillakfc.listeners;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;

public final class DurabilityListener implements Listener {

    private static final Map<Material, Integer> CUSTOM_DURABILITY = Map.ofEntries(
        // Iron tools
        Map.entry(Material.IRON_SWORD,      400),
        Map.entry(Material.IRON_PICKAXE,    400),
        Map.entry(Material.IRON_AXE,        400),
        Map.entry(Material.IRON_SHOVEL,     400),
        Map.entry(Material.IRON_HOE,        400),
        // Iron armor
        Map.entry(Material.IRON_HELMET,     240),
        Map.entry(Material.IRON_CHESTPLATE, 330),
        Map.entry(Material.IRON_LEGGINGS,   310),
        Map.entry(Material.IRON_BOOTS,      275),
        // Diamond tools
        Map.entry(Material.DIAMOND_SWORD,      2000),
        Map.entry(Material.DIAMOND_PICKAXE,    2000),
        Map.entry(Material.DIAMOND_AXE,        2000),
        Map.entry(Material.DIAMOND_SHOVEL,     2000),
        Map.entry(Material.DIAMOND_HOE,        2000),
        // Diamond armor
        Map.entry(Material.DIAMOND_HELMET,     500),
        Map.entry(Material.DIAMOND_CHESTPLATE, 700),
        Map.entry(Material.DIAMOND_LEGGINGS,   650),
        Map.entry(Material.DIAMOND_BOOTS,      575)
    );

    @EventHandler
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        Integer target = CUSTOM_DURABILITY.get(item.getType());
        if (target == null) return;

        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return;
        // getMaxDamage() throws unless hasMaxDamage() is true (only set once
        // something explicitly overrides it - most items never have it set).
        if (damageable.hasMaxDamage() && damageable.getMaxDamage() == target) return;

        damageable.setMaxDamage(target);
        item.setItemMeta(meta);
    }
}
