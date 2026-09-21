package com.example.vanillakfc.listeners;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Vanilla Fortune on ore-type blocks (coal, copper, diamond, emerald, gold,
 * iron, lapis, nether gold, nether quartz, amethyst clusters) picks a
 * multiplier of 1..level+1 via {@code minecraft:ore_drops}, roughly uniform
 * after clamping a negative roll to zero. This replaces that with an
 * explicit probability table per level. Blocks using a different bonus
 * formula (redstone, glowstone, etc.) are untouched here; the crops among
 * them are handled separately by {@link CropFortuneListener}, which takes
 * Fortune off them entirely.
 *
 * <p>Lapis Ore is a special case: unlike the other ores here (which vanilla
 * already drops as a flat 1 without Fortune), vanilla gives it a randomized
 * 4-9 base with no Fortune at all. That base is flattened to 1 here too, so
 * it behaves like every other ore in this list - Fortune is what scales the
 * count, not the base roll.
 */
public final class FortuneLootListener implements Listener {

    private static final Set<Material> FORTUNE_ORE_DROPS = EnumSet.of(
            Material.AMETHYST_CLUSTER,
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE
    );

    private static final Set<Material> LAPIS_ORE = EnumSet.of(Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE);
    private static final int LAPIS_BASE_DROP_COUNT = 1;

    // Cumulative probabilities for count = index + 1, per Fortune level.
    private static final double[][] CUMULATIVE_CHANCE = {
            {0.60, 1.00},             // Fortune I:   1x 60%, 2x 40%
            {0.40, 0.90, 1.00},       // Fortune II:  1x 40%, 2x 50%, 3x 10%
            {0.20, 0.80, 0.90, 1.00}, // Fortune III: 1x 20%, 2x 60%, 3x 10%, 4x 10%
    };

    @EventHandler
    public void onBlockDropItem(BlockDropItemEvent event) {
        Material blockType = event.getBlockState().getType();
        if (!FORTUNE_ORE_DROPS.contains(blockType)) return;

        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        ItemMeta meta = tool.getItemMeta();
        if (meta == null) return;
        if (meta.getEnchantLevel(Enchantment.SILK_TOUCH) > 0) return;

        int fortuneLevel = meta.getEnchantLevel(Enchantment.FORTUNE);

        int count;
        if (fortuneLevel >= 1 && fortuneLevel <= CUMULATIVE_CHANCE.length) {
            count = rollCount(fortuneLevel);
        } else if (LAPIS_ORE.contains(blockType)) {
            count = LAPIS_BASE_DROP_COUNT;
        } else {
            return; // no Fortune, and vanilla's own unmodified base is already fine
        }

        for (Item item : event.getItems()) {
            item.getItemStack().setAmount(count);
        }
    }

    private static int rollCount(int fortuneLevel) {
        double[] cumulative = CUMULATIVE_CHANCE[fortuneLevel - 1];
        double roll = ThreadLocalRandom.current().nextDouble();
        for (int i = 0; i < cumulative.length; i++) {
            if (roll < cumulative[i]) {
                return i + 1;
            }
        }
        return cumulative.length;
    }
}
