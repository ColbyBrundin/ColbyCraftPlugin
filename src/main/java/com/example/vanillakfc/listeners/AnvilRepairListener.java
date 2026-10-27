package com.example.vanillakfc.listeners;

import com.example.vanillakfc.advancements.Advancements;
import com.example.vanillakfc.recipes.CustomRecipes;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

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
 * <p>An Elytra is repaired in full by a single Phantom Membrane, rather than
 * a quarter per membrane.
 *
 * <p>Repairing and combining are priced separately. Vanilla keeps one
 * prior-work penalty per item (its {@code repair_cost}) that every anvil use
 * charges and raises, so a much-enchanted item was dear to repair and a
 * much-repaired one dear to enchant. Here every repair above charges and
 * raises a counter of its own, stored on the item under
 * {@code vanillakfc:repair_penalty}, and leaves vanilla's penalty alone for
 * combining (books, or two items) to use as vanilla does; neither is ever
 * charged for the other's kind of work. Combining two items keeps the higher
 * of their two repair counters, and vanilla already keeps the higher of their
 * combining penalties (before raising it for the combine). A repair costs its
 * counter plus one level per unit of material and takes the counter from
 * {@code n} to {@code ceil(1.5n + 1)}: 0, 1, 3, 6, 10, 16, 25, 39, so an item
 * gets seven repairs before "Too Expensive!" (vanilla's shared penalty, at
 * {@code 2n + 1}, allowed six).
 *
 * <p>Items from before the split carry one number for both, with no record of
 * what built it, so each starts over: the first time it goes in an anvil,
 * vanilla's penalty is cleared and the repair counter set to 0. An item
 * counts as converted once it carries the counter, so every anvil result with
 * a penalty gets one - otherwise every later combine would be wiped again.
 */
public final class AnvilRepairListener implements Listener {

    private static final int BASE_SLOT = 0;
    private static final int MATERIAL_SLOT = 1;
    private static final int RESULT_SLOT = 2;

    private static final String TRIDENT_ADVANCEMENT = "oh_neptune";

    private final Plugin plugin;
    private final Advancements advancements;
    private final NamespacedKey repairPenaltyKey;
    // Anvils whose inputs were just converted, so the result vanilla worked out
    // from the old penalty is stale until they are re-checked next tick.
    private final Set<InventoryView> converting = Collections.newSetFromMap(new WeakHashMap<>());

    public AnvilRepairListener(Plugin plugin, Advancements advancements) {
        this.plugin = plugin;
        this.advancements = advancements;
        this.repairPenaltyKey = new NamespacedKey(plugin, "repair_penalty");
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

    /**
     * Converts pre-split items as they go in, before anything is priced.
     *
     * <p>The slot items are live, so they are converted where they sit. By now
     * vanilla has already worked out a result from the old penalty, so that is
     * hidden for this pass and the anvil is re-checked next tick, when it
     * prices the converted items.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPrepareAnvilConvert(PrepareAnvilEvent event) {
        AnvilInventory anvil = event.getInventory();
        boolean baseConverted = convertOldPenalty(anvil.getItem(BASE_SLOT));
        boolean materialConverted = convertOldPenalty(anvil.getItem(MATERIAL_SLOT));
        if (!baseConverted && !materialConverted) return;

        event.setResult(null);
        converting.add(event.getView());
        Bukkit.getScheduler().runTask(plugin, () -> anvil.setItem(BASE_SLOT, anvil.getItem(BASE_SLOT)));
    }

    private boolean convertOldPenalty(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        if (item.getPersistentDataContainer().has(repairPenaltyKey)) return false;

        if (item.getDataOrDefault(DataComponentTypes.REPAIR_COST, 0) <= 0) return false;

        item.setData(DataComponentTypes.REPAIR_COST, 0);
        item.editPersistentDataContainer(pdc -> pdc.set(repairPenaltyKey, PersistentDataType.INTEGER, 0));
        return true;
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (converting.remove(event.getView())) return;

        ItemStack base = event.getInventory().getItem(BASE_SLOT);
        ItemStack material = event.getInventory().getItem(MATERIAL_SLOT);
        if (base == null || material == null) return;

        // One Phantom Membrane mends an Elytra completely, where vanilla's
        // restores a quarter each.
        if (base.getType() == Material.ELYTRA && material.getType() == Material.PHANTOM_MEMBRANE) {
            int damage = damage(base);
            if (damage > 0) repairWithMaterial(event, base, 1, 0);
            return;
        }

        Material repairMaterial = ITEM_TO_REPAIR_MATERIAL.get(base.getType());
        if (repairMaterial != null) {
            // Ingots no longer repair the tiers - block the old behavior
            // entirely. Nothing to block for the trident, which vanilla never
            // let a material repair in the first place, or for netherite,
            // whose ingot is the repair material.
            if (material.getType() != repairMaterial && material.getType() == ITEM_TO_INGOT.get(base.getType())) {
                event.setResult(null);
                return;
            }
            if (material.getType() == repairMaterial) {
                repairPerUnit(event, base, material, (int) Math.ceil(maxDamage(base) * REPAIR_FRACTION_PER_UNIT));
                return;
            }
        } else if (base.isRepairableBy(material)) {
            // Everything else vanilla repairs with a material (copper, leather,
            // wood and stone gear, chainmail, shields, the mace, ...) keeps
            // vanilla's quarter per unit but is priced like the repairs above.
            repairPerUnit(event, base, material, maxDamage(base) / 4);
            return;
        }

        combine(event, base, material);
    }

    private void repairPerUnit(PrepareAnvilEvent event, ItemStack base, ItemStack material, int repairPerUnit) {
        int damage = damage(base);
        if (damage <= 0 || repairPerUnit <= 0) return;

        int units = Math.min(material.getAmount(), (int) Math.ceil((double) damage / repairPerUnit));
        repairWithMaterial(event, base, units, Math.max(0, damage - units * repairPerUnit));
    }

    /**
     * Prices a material repair on the repair counter alone - the counter plus
     * one level per unit - and raises only that counter. The result keeps the
     * input's combining penalty as it was.
     *
     * <p>Vanilla's own result is kept where it made one, as it carries a
     * rename done alongside (a block repair, which vanilla can't do, never
     * has one); only its durability and penalties are set. Where vanilla
     * refused the repair as "Too Expensive!" on the combining penalty, which
     * doesn't apply to a repair, the result is built from the input instead -
     * without the new name.
     */
    private void repairWithMaterial(PrepareAnvilEvent event, ItemStack base, int units, int newDamage) {
        if (units <= 0) return;

        int priorPenalty = repairPenalty(base);
        int levelCost = priorPenalty + units;
        if (tooExpensive(event.getView(), levelCost)) {
            event.setResult(null);
            return;
        }

        ItemStack result = event.getResult();
        if (result == null || result.isEmpty() || result.getType() != base.getType()) result = base.clone();
        if (!(result.getItemMeta() instanceof Damageable resultMeta)) return;

        resultMeta.setDamage(newDamage);
        result.setItemMeta(resultMeta);
        result.setData(DataComponentTypes.REPAIR_COST, base.getDataOrDefault(DataComponentTypes.REPAIR_COST, 0));
        setRepairPenalty(result, increasedPenalty(priorPenalty));

        event.setResult(result);
        event.getView().setRepairItemCountCost(units);
        event.getView().setRepairCost(levelCost);
    }

    /**
     * Combining (an enchanted book, or a second item of the same kind) is
     * vanilla's own operation and priced as vanilla prices it, on the
     * combining penalty - the repair counter never enters into it.
     *
     * <p>The result takes the higher of the two items' combining penalties,
     * raised once for this combine ({@code n} to {@code 2n + 1}), and the
     * higher of their repair counters, unraised - so merging a much-worked
     * item into a fresh one loses neither history. Vanilla already works the
     * combining penalty out this way; it's set here too so it doesn't hang on
     * vanilla's internals. A book's repair counter is always 0, so combining
     * with one keeps the item's own.
     */
    private void combine(PrepareAnvilEvent event, ItemStack base, ItemStack addition) {
        ItemStack result = event.getResult();
        if (result == null || result.isEmpty()) return;
        if (addition.getType() != base.getType() && addition.getType() != Material.ENCHANTED_BOOK) return;

        int combiningPenalty = Math.max(
                base.getDataOrDefault(DataComponentTypes.REPAIR_COST, 0),
                addition.getDataOrDefault(DataComponentTypes.REPAIR_COST, 0));
        result.setData(DataComponentTypes.REPAIR_COST, (int) Math.min(Integer.MAX_VALUE, 2L * combiningPenalty + 1));
        setRepairPenalty(result, Math.max(repairPenalty(base), repairPenalty(addition)));
        event.setResult(result);
    }

    private static int damage(ItemStack item) {
        return item.getItemMeta() instanceof Damageable damageable ? damageable.getDamage() : 0;
    }

    // Damageable#getMaxDamage() throws unless hasMaxDamage() is true (it's
    // only set once something explicitly overrides it); most items never get
    // an explicit override, so fall back to the material's vanilla default
    // durability.
    private static int maxDamage(ItemStack item) {
        if (item.getItemMeta() instanceof Damageable damageable && damageable.hasMaxDamage()) {
            return damageable.getMaxDamage();
        }
        return item.getType().getMaxDurability();
    }

    /**
     * Gives every anvil result that carries a combining penalty the repair
     * counter too, so it's never mistaken for a pre-split item later. Items
     * with no penalty at all are left unmarked, so a renamed stack of blocks
     * still stacks with the rest.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvilMark(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.isEmpty()) return;
        if (result.getPersistentDataContainer().has(repairPenaltyKey)) return;
        if (result.getDataOrDefault(DataComponentTypes.REPAIR_COST, 0) <= 0) return;

        setRepairPenalty(result, 0);
        event.setResult(result);
    }

    // Vanilla refuses an anvil operation costing at or above the cap ("Too
    // Expensive!"), but it runs that check before PrepareAnvilEvent, against
    // its own pricing, so it is re-applied to these repairs here. Creative
    // players are exempt, as in vanilla.
    private static boolean tooExpensive(AnvilView view, int levelCost) {
        boolean creative = view.getPlayer() instanceof Player player && player.getGameMode() == GameMode.CREATIVE;
        return levelCost >= view.getMaximumRepairCost() && !creative;
    }

    private int repairPenalty(ItemStack item) {
        return item.getPersistentDataContainer().getOrDefault(repairPenaltyKey, PersistentDataType.INTEGER, 0);
    }

    private void setRepairPenalty(ItemStack item, int penalty) {
        item.editPersistentDataContainer(pdc -> pdc.set(repairPenaltyKey, PersistentDataType.INTEGER, penalty));
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

    // One repair raises the counter once, however many units it used -
    // matching vanilla, which charges per operation, not per material.
    private static int increasedPenalty(int prior) {
        long increased = (long) Math.ceil(prior * PENALTY_GROWTH_MULTIPLIER) + 1;
        return (int) Math.min(Integer.MAX_VALUE, increased);
    }
}
