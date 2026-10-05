package com.example.vanillakfc.listeners;

import com.example.vanillakfc.advancements.Advancements;
import com.example.vanillakfc.recipes.CustomRecipes;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Reworks anvil repair-with-material so it costs a block of the tier
 * material (iron/gold/diamond) per 75% of durability restored, instead of a
 * single ingot. Ingots no longer repair anything for these tiers - this
 * replaces, rather than supplements, vanilla's ingot repair.
 *
 * <p>Netherite is the exception: a single Netherite Ingot restores the 75%,
 * not a Netherite Block.
 *
 * <p>A trident is repaired the same way by a Heart of the Sea, 75% each.
 * That one is purely an addition: vanilla offers no repair material for a
 * trident at all, so combining two tridents and Mending were the only ways to
 * mend one, and both still work.
 *
 * <p>Each such repair also raises the item's prior-work penalty - the hidden
 * counter behind every future anvil cost - by 50% more than a vanilla anvil
 * use would. Vanilla takes a penalty of {@code n} to {@code 2n + 1}, an
 * increase of {@code n + 1}; here the increase is {@code ceil(1.5 * (n + 1))}.
 * From a fresh item that runs 0, 2, 7, 19, 49 against vanilla's 0, 1, 3, 7,
 * 15, 31, so an item hits "Too Expensive!" after four repairs instead of six.
 *
 * <p>The level cost follows vanilla's formula too: the item's existing
 * penalty plus one level per unit of material. An earlier version left both out - the
 * result was a straight copy of the input, so block repairs never raised the
 * penalty at all and always cost just one level per block, however worn the
 * item's anvil history.
 */
public final class AnvilRepairListener implements Listener {

    private static final int BASE_SLOT = 0;
    private static final int MATERIAL_SLOT = 1;
    private static final int RESULT_SLOT = 2;

    private static final String TRIDENT_ADVANCEMENT = "oh_neptune";

    private final Plugin plugin;
    private final Advancements advancements;

    public AnvilRepairListener(Plugin plugin, Advancements advancements) {
        this.plugin = plugin;
        this.advancements = advancements;
    }

    private static final double REPAIR_FRACTION_PER_UNIT = 0.75;
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

    /**
     * What each repairable item is mended with, one unit per
     * {@link #REPAIR_FRACTION_PER_UNIT} of its durability.
     *
     * <p>Every tier item takes a block of its own material, except netherite,
     * which takes the ingot itself. The trident is the
     * one entry that isn't a tier item: vanilla gives it no repair material at
     * all - only a second trident or Mending - so a Heart of the Sea is added
     * here rather than replacing anything, on the same terms as the blocks.
     */
    private static final Map<Material, Material> ITEM_TO_REPAIR_MATERIAL = buildItemToRepairMaterial();

    private static Map<Material, Material> buildItemToRepairMaterial() {
        Map<Material, Material> map = new EnumMap<>(Material.class);
        for (Map.Entry<Material, Material> entry : ITEM_TO_INGOT.entrySet()) {
            if (entry.getValue() == Material.NETHERITE_INGOT) {
                map.put(entry.getKey(), Material.NETHERITE_INGOT);
                continue;
            }
            Material block = CustomRecipes.INGOT_TO_BLOCK.get(entry.getValue());
            if (block != null) {
                map.put(entry.getKey(), block);
            }
        }
        map.put(Material.TRIDENT, Material.HEART_OF_THE_SEA);
        return map;
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack base = event.getInventory().getItem(BASE_SLOT);
        ItemStack material = event.getInventory().getItem(MATERIAL_SLOT);
        if (base == null || material == null) return;

        Material repairMaterial = ITEM_TO_REPAIR_MATERIAL.get(base.getType());
        if (repairMaterial == null) return;

        // Ingots no longer repair the tiers - block the old behavior entirely.
        // Nothing to block for the trident, which vanilla never let a material
        // repair in the first place, or for netherite, whose ingot is the
        // repair material.
        if (material.getType() != repairMaterial && material.getType() == ITEM_TO_INGOT.get(base.getType())) {
            event.setResult(null);
            return;
        }

        if (material.getType() != repairMaterial) return;

        ItemMeta baseMeta = base.getItemMeta();
        if (!(baseMeta instanceof Damageable damageable) || damageable.getDamage() <= 0) return;

        // Damageable#getMaxDamage() throws unless hasMaxDamage() is true (it's
        // only set once something explicitly overrides it); most items never
        // get an explicit override, so fall back to the material's vanilla
        // default durability.
        int maxDamage = damageable.hasMaxDamage() ? damageable.getMaxDamage() : base.getType().getMaxDurability();
        int currentDamage = damageable.getDamage();
        int repairPerUnit = (int) Math.ceil(maxDamage * REPAIR_FRACTION_PER_UNIT);
        int unitsNeeded = (int) Math.ceil((double) currentDamage / repairPerUnit);
        int unitsToConsume = Math.min(material.getAmount(), unitsNeeded);
        if (unitsToConsume <= 0) return;

        int priorPenalty = baseMeta instanceof Repairable repairable ? repairable.getRepairCost() : 0;
        int levelCost = priorPenalty + unitsToConsume;

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
        ((Damageable) resultMeta).setDamage(Math.max(0, currentDamage - unitsToConsume * repairPerUnit));
        if (resultMeta instanceof Repairable repairable) {
            repairable.setRepairCost(increasedPenalty(priorPenalty));
        }
        result.setItemMeta(resultMeta);

        event.setResult(result);
        view.setRepairItemCountCost(unitsToConsume);
        view.setRepairCost(levelCost);
    }

    /**
     * Awards "Oh Neptune" for a trident actually mended with a Heart of the
     * Sea.
     *
     * <p>Hung off taking the result rather than {@link PrepareAnvilEvent},
     * which only means the anvil showed a preview - a player who opens an
     * anvil, sees the result and walks away has repaired nothing. There is no
     * "anvil used" event to listen for, so the check is whether the Heart was
     * actually consumed: read a tick later, because at click time the anvil
     * has not applied anything yet, and the take can still fail on levels or
     * on "Too Expensive!".
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTakeResult(InventoryClickEvent event) {
        if (event.getRawSlot() != RESULT_SLOT) return;
        if (!(event.getView() instanceof AnvilView view)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory anvil = view.getTopInventory();
        ItemStack base = anvil.getItem(BASE_SLOT);
        ItemStack material = anvil.getItem(MATERIAL_SLOT);
        if (base == null || base.getType() != Material.TRIDENT) return;
        if (material == null || material.getType() != Material.HEART_OF_THE_SEA) return;

        int hearts = material.getAmount();
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack left = anvil.getItem(MATERIAL_SLOT);
            boolean consumed = left == null
                    || left.getType() != Material.HEART_OF_THE_SEA
                    || left.getAmount() < hearts;
            if (consumed) {
                advancements.award(player, TRIDENT_ADVANCEMENT);
            }
        });
    }

    // One anvil operation raises the penalty once, however many units it used -
    // matching vanilla, which charges per operation, not per material.
    private static int increasedPenalty(int prior) {
        long vanillaIncrease = (long) prior + 1;
        long increased = prior + (long) Math.ceil(vanillaIncrease * PENALTY_GROWTH_MULTIPLIER);
        return (int) Math.min(Integer.MAX_VALUE, increased);
    }
}
