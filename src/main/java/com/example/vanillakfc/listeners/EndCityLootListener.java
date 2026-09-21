package com.example.vanillakfc.listeners;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.keys.tags.EnchantmentTagKeys;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.LootTables;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reweights end city treasure chest loot against vanilla's weights:
 *  - Enchanted diamond gear (sword, spear, boots, chestplate, leggings,
 *    helmet, pickaxe, shovel): 3 -> 1 each.
 *  - Gold ingot: 15 -> 20. Iron ingot: 10 -> 15. Diamond: 5 -> 7.
 *    Emerald: 2 -> 3.
 *
 * Applied post-hoc to vanilla's own generated loot rather than
 * reimplementing the pool: each roll's real, already-enchanted item is
 * kept, dropped, or duplicated with probability derived from
 * newWeight/oldWeight, scaling that item's expected occurrence rate by
 * exactly that factor without needing to replicate vanilla's random
 * enchantment selection ourselves.
 *
 * <p>Vanilla enchants the diamond (and iron) gear here from
 * {@code #minecraft:on_random_loot} (levels 20-39), which includes four
 * treasure enchantments - Mending, Frost Walker, and both curses - that a
 * normal enchanting table could never roll. For diamond gear specifically,
 * any treasure enchantment vanilla picked is discarded and re-rolled using
 * only non-treasure enchantments, matching what an enchanting table
 * ({@code #minecraft:in_enchanting_table}) would offer. The re-roll reads
 * each enchantment's real registry data (tag membership, weight, cost
 * range, item compatibility) live from the server rather than a
 * hardcoded pool, so it stays correct even if enchantment data is
 * changed by a datapack.
 */
public final class EndCityLootListener implements Listener {

    private static final Set<Material> DIAMOND_GEAR = EnumSet.of(
            Material.DIAMOND_SWORD, Material.DIAMOND_SPEAR, Material.DIAMOND_BOOTS,
            Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_HELMET,
            Material.DIAMOND_PICKAXE, Material.DIAMOND_SHOVEL
    );

    // Matches the levels range on the diamond/iron gear entries in
    // data/minecraft/loot_table/chests/end_city_treasure.json.
    private static final int MIN_POWER = 20;
    private static final int MAX_POWER = 39;

    private static final Registry<Enchantment> ENCHANTMENT_REGISTRY =
            RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
    private static final Set<Enchantment> IN_ENCHANTING_TABLE =
            new HashSet<>(ENCHANTMENT_REGISTRY.getTagValues(EnchantmentTagKeys.IN_ENCHANTING_TABLE));

    private static final Map<Material, Double> WEIGHT_FACTOR = new EnumMap<>(Material.class);

    static {
        for (Material diamondGear : DIAMOND_GEAR) {
            WEIGHT_FACTOR.put(diamondGear, 1.0 / 3.0);
        }
        WEIGHT_FACTOR.put(Material.GOLD_INGOT, 20.0 / 15.0);
        WEIGHT_FACTOR.put(Material.IRON_INGOT, 15.0 / 10.0);
        WEIGHT_FACTOR.put(Material.DIAMOND, 7.0 / 5.0);
        WEIGHT_FACTOR.put(Material.EMERALD, 3.0 / 2.0);
    }

    @EventHandler
    public void onLootGenerate(LootGenerateEvent event) {
        if (!event.getLootTable().getKey().equals(LootTables.END_CITY_TREASURE.getKey())) return;

        List<ItemStack> reweighted = new ArrayList<>();
        for (ItemStack item : event.getLoot()) {
            if (DIAMOND_GEAR.contains(item.getType())) {
                rerollIfTreasureEnchant(item);
            }

            Double factor = WEIGHT_FACTOR.get(item.getType());
            if (factor == null) {
                reweighted.add(item);
                continue;
            }

            int copies = (int) Math.floor(factor);
            double remainder = factor - copies;
            if (ThreadLocalRandom.current().nextDouble() < remainder) copies++;

            for (int i = 0; i < copies; i++) {
                reweighted.add(item.clone());
            }
        }

        event.setLoot(reweighted);
    }

    private record Candidate(Enchantment enchantment, int level) {}

    private static void rerollIfTreasureEnchant(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        Map<Enchantment, Integer> enchants = meta.getEnchants();
        if (enchants.keySet().stream().allMatch(IN_ENCHANTING_TABLE::contains)) return;

        for (Enchantment old : enchants.keySet()) {
            meta.removeEnchant(old);
        }

        int power = ThreadLocalRandom.current().nextInt(MIN_POWER, MAX_POWER + 1);
        List<Candidate> candidates = new ArrayList<>();
        int totalWeight = 0;
        for (Enchantment enchantment : ENCHANTMENT_REGISTRY) {
            if (!IN_ENCHANTING_TABLE.contains(enchantment) || !enchantment.canEnchantItem(item)) continue;
            for (int level = enchantment.getStartLevel(); level <= enchantment.getMaxLevel(); level++) {
                if (power >= enchantment.getMinModifiedCost(level) && power <= enchantment.getMaxModifiedCost(level)) {
                    candidates.add(new Candidate(enchantment, level));
                    totalWeight += enchantment.getWeight();
                }
            }
        }

        if (candidates.isEmpty()) {
            item.setItemMeta(meta);
            return;
        }

        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        for (Candidate candidate : candidates) {
            roll -= candidate.enchantment().getWeight();
            if (roll < 0) {
                meta.addEnchant(candidate.enchantment(), candidate.level(), true);
                break;
            }
        }

        item.setItemMeta(meta);
    }
}
