package com.example.vanillakfc.listeners;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Doubles the saturation modifier of farm-animal meats and farmland-crop
 * foods (raw and cooked/baked alike), plus bread. Nutrition (hunger
 * restored) is untouched - only how filling each bite is changes.
 *
 * <p>Reads each item's current effective food component and doubles
 * whatever saturation is already on it, rather than hardcoding vanilla's
 * numbers, so it stays correct if those defaults ever change.
 *
 * <p>Scope is deliberately narrow: the five breedable meat animals' raw and
 * cooked drops (beef, pork, chicken, mutton, rabbit) - fish and rotten flesh
 * are excluded as not "animal meat" in the usual sense - and the plain
 * farmland crop foods (carrot, potato/baked potato, beetroot, melon slice)
 * plus bread. Composite dishes (pumpkin pie, beetroot soup, stews) and
 * non-farmland foods (apples, berries, golden carrot) are excluded since
 * they're not plain raw/cooked crop items.
 */
public final class FoodSaturationListener implements Listener {

    private static final Set<Material> DOUBLED_SATURATION_FOODS = EnumSet.of(
            Material.BEEF, Material.COOKED_BEEF,
            Material.PORKCHOP, Material.COOKED_PORKCHOP,
            Material.CHICKEN, Material.COOKED_CHICKEN,
            Material.MUTTON, Material.COOKED_MUTTON,
            Material.RABBIT, Material.COOKED_RABBIT,
            Material.CARROT,
            Material.POTATO, Material.BAKED_POTATO,
            Material.BEETROOT,
            Material.MELON_SLICE,
            Material.BREAD
    );

    private static final float SATURATION_MULTIPLIER = 2.0F;

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        if (!DOUBLED_SATURATION_FOODS.contains(item.getType())) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasFood()) {
            return;
        }

        FoodComponent food = meta.getFood();
        food.setSaturation(food.getSaturation() * SATURATION_MULTIPLIER);
        meta.setFood(food);
        item.setItemMeta(meta);

        event.setItem(item);
    }
}
