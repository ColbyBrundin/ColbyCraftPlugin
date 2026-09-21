package com.example.vanillakfc.listeners;

import com.example.vanillakfc.recipes.CustomRecipes;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.view.AnvilView;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Reworks anvil repair-with-material so it costs a block of the tier
 * material (iron/gold/diamond/netherite) per 75% of durability restored,
 * instead of a single ingot. Ingots no longer repair anything for these
 * tiers - this replaces, rather than supplements, vanilla's ingot repair.
 *
 * <p>Each block repair also raises the item's prior-work penalty - the hidden
 * counter behind every future anvil cost - by 50% more than a vanilla anvil
 * use would. Vanilla takes a penalty of {@code n} to {@code 2n + 1}, an
 * increase of {@code n + 1}; here the increase is {@code ceil(1.5 * (n + 1))}.
 * From a fresh item that runs 0, 2, 7, 19, 49 against vanilla's 0, 1, 3, 7,
 * 15, 31, so an item hits "Too Expensive!" after four repairs instead of six.
 *
 * <p>The level cost follows vanilla's formula too: the item's existing
 * penalty plus one level per block. An earlier version left both out - the
 * result was a straight copy of the input, so block repairs never raised the
 * penalty at all and always cost just one level per block, however worn the
 * item's anvil history.
 */
public final class AnvilRepairListener implements Listener {

    private static final double REPAIR_FRACTION_PER_BLOCK = 0.75;
    private static final double PENALTY_GROWTH_MULTIPLIER = 1.5;

    private static final Set<Material> IRON_ITEMS = EnumSet.of(
            Material.IRON_SWORD, Material.IRON_PICKAXE, Material.IRON_AXE,
            Material.IRON_SHOVEL, Material.IRON_HOE, Material.IRON_SPEAR,
            Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS
    );
    private static final Set<Material> GOLD_ITEMS = EnumSet.of(
            Material.GOLDEN_SWORD, Material.GOLDEN_PICKAXE, Material.GOLDEN_AXE,
            Material.GOLDEN_SHOVEL, Material.GOLDEN_HOE, Material.GOLDEN_SPEAR,
            Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS
    );
    private static final Set<Material> DIAMOND_ITEMS = EnumSet.of(
            Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE,
            Material.DIAMOND_SHOVEL, Material.DIAMOND_HOE, Material.DIAMOND_SPEAR,
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS
    );
    private static final Set<Material> NETHERITE_ITEMS = EnumSet.of(
            Material.NETHERITE_SWORD, Material.NETHERITE_PICKAXE, Material.NETHERITE_AXE,
            Material.NETHERITE_SHOVEL, Material.NETHERITE_HOE, Material.NETHERITE_SPEAR,
            Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS
    );

    private static final Map<Material, Material> ITEM_TO_INGOT = buildItemToIngot();

    private static Map<Material, Material> buildItemToIngot() {
        Map<Material, Material> map = new EnumMap<>(Material.class);
        for (Material m : IRON_ITEMS) map.put(m, Material.IRON_INGOT);
        for (Material m : GOLD_ITEMS) map.put(m, Material.GOLD_INGOT);
        for (Material m : DIAMOND_ITEMS) map.put(m, Material.DIAMOND);
        for (Material m : NETHERITE_ITEMS) map.put(m, Material.NETHERITE_INGOT);
        return map;
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack base = event.getInventory().getItem(0);
        ItemStack material = event.getInventory().getItem(1);
        if (base == null || material == null) return;

        Material ingot = ITEM_TO_INGOT.get(base.getType());
        if (ingot == null) return;

        if (material.getType() == ingot) {
            // Ingots no longer repair this tier - block the old behavior entirely.
            event.setResult(null);
            return;
        }

        Material block = CustomRecipes.INGOT_TO_BLOCK.get(ingot);
        if (block == null || material.getType() != block) return;

        ItemMeta baseMeta = base.getItemMeta();
        if (!(baseMeta instanceof Damageable damageable) || damageable.getDamage() <= 0) return;

        // Damageable#getMaxDamage() throws unless hasMaxDamage() is true (it's
        // only set once something explicitly overrides it); most items never
        // get an explicit override, so fall back to the material's vanilla
        // default durability.
        int maxDamage = damageable.hasMaxDamage() ? damageable.getMaxDamage() : base.getType().getMaxDurability();
        int currentDamage = damageable.getDamage();
        int repairPerBlock = (int) Math.ceil(maxDamage * REPAIR_FRACTION_PER_BLOCK);
        int blocksNeeded = (int) Math.ceil((double) currentDamage / repairPerBlock);
        int blocksToConsume = Math.min(material.getAmount(), blocksNeeded);
        if (blocksToConsume <= 0) return;

        int priorPenalty = baseMeta instanceof Repairable repairable ? repairable.getRepairCost() : 0;
        int levelCost = priorPenalty + blocksToConsume;

        // Vanilla refuses an anvil operation costing at or above the cap
        // ("Too Expensive!"), but it runs that check before this event, so an
        // overridden result skips it entirely. Re-applied here so that raising
        // the penalty actually ends an item's repair life the way vanilla's
        // does. Creative players are exempt, as in vanilla.
        AnvilView view = event.getView();
        boolean creative = view.getPlayer() instanceof Player player && player.getGameMode() == GameMode.CREATIVE;
        if (levelCost >= view.getMaximumRepairCost() && !creative) {
            event.setResult(null);
            return;
        }

        ItemStack result = base.clone();
        ItemMeta resultMeta = result.getItemMeta();
        ((Damageable) resultMeta).setDamage(Math.max(0, currentDamage - blocksToConsume * repairPerBlock));
        if (resultMeta instanceof Repairable repairable) {
            repairable.setRepairCost(increasedPenalty(priorPenalty));
        }
        result.setItemMeta(resultMeta);

        event.setResult(result);
        view.setRepairItemCountCost(blocksToConsume);
        view.setRepairCost(levelCost);
    }

    // One anvil operation raises the penalty once, however many blocks it
    // used - matching vanilla, which charges per operation, not per material.
    private static int increasedPenalty(int prior) {
        long vanillaIncrease = (long) prior + 1;
        long increased = prior + (long) Math.ceil(vanillaIncrease * PENALTY_GROWTH_MULTIPLIER);
        return (int) Math.min(Integer.MAX_VALUE, increased);
    }
}
