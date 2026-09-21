package com.example.vanillakfc.listeners;

import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Fortune no longer multiplies crop yields. Breaking a mature crop with a
 * Fortune tool drops exactly what the same break would have dropped with an
 * unenchanted one - farming scales by planting more, not by enchanting the
 * hoe.
 *
 * <p>Only the crops vanilla actually scales are listed: wheat and beetroot
 * (their seeds), carrots and potatoes (the vegetable itself), nether wart and
 * melon blocks. Everything else a Fortune tool can break is left to vanilla,
 * including the ore blocks {@link FortuneLootListener} retunes.
 *
 * <p>Rather than hard-coding what each crop drops, the no-Fortune result is
 * re-rolled from the broken block's own loot table via
 * {@link BlockState#getDrops(ItemStack, org.bukkit.entity.Entity)}, handing it
 * a copy of the tool with Fortune stripped off. That keeps the yields exactly
 * vanilla's - including the parts Fortune never touched, like a potato's 2%
 * poisonous potato - with no table here to drift out of date when a crop is
 * retuned upstream.
 *
 * <p>A re-roll can land above what Fortune happened to produce (both are
 * random, and a stingy Fortune roll loses to a generous plain one often
 * enough to matter - a Fortune I hoe draws zero wheat seeds about 3% of the
 * time, where the plain roll usually would not). Clamping downward only would
 * quietly make crops worse than vanilla, so a shortfall is made up by
 * dropping the difference directly.
 */
public final class CropFortuneListener implements Listener {

    private static final Set<Material> FORTUNE_SCALED_CROPS = EnumSet.of(
            Material.WHEAT,
            Material.CARROTS,
            Material.POTATOES,
            Material.BEETROOTS,
            Material.NETHER_WART,
            Material.MELON
    );

    @EventHandler(ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event) {
        BlockState state = event.getBlockState();
        if (!FORTUNE_SCALED_CROPS.contains(state.getType())) return;

        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        ItemMeta meta = tool.getItemMeta();
        if (meta == null || meta.getEnchantLevel(Enchantment.FORTUNE) <= 0) return;

        // The rest of the tool is kept as-is, so anything else the loot table
        // reads off it still applies exactly as vanilla would apply it.
        ItemStack withoutFortune = tool.clone();
        withoutFortune.removeEnchantment(Enchantment.FORTUNE);

        // Totalled per material, because one crop can drop the same item from
        // several loot pools - a carrot's guaranteed one and its bonus roll
        // arrive as two separate stacks.
        Map<Material, Integer> wanted = new EnumMap<>(Material.class);
        for (ItemStack drop : state.getDrops(withoutFortune, player)) {
            wanted.merge(drop.getType(), drop.getAmount(), Integer::sum);
        }

        // The first stack of a material takes that material's whole total and
        // any further stacks of it drop out, which is why the amount is only
        // ever assigned, never accumulated.
        for (Iterator<Item> items = event.getItems().iterator(); items.hasNext(); ) {
            Item item = items.next();
            ItemStack stack = item.getItemStack();
            int remaining = wanted.getOrDefault(stack.getType(), 0);
            if (remaining <= 0) {
                items.remove();
                continue;
            }
            wanted.put(stack.getType(), 0);
            stack.setAmount(remaining);
            item.setItemStack(stack);
        }

        // Whatever the re-roll asked for that Fortune didn't drop at all.
        // Spawned directly instead of being added to the event's list, since
        // that list only carries entities the server already built for this
        // break.
        for (Map.Entry<Material, Integer> shortfall : wanted.entrySet()) {
            if (shortfall.getValue() <= 0) continue;
            state.getWorld().dropItemNaturally(
                    state.getLocation().add(0.5, 0.5, 0.5),
                    new ItemStack(shortfall.getKey(), shortfall.getValue()));
        }
    }
}
