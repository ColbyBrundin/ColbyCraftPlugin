package com.example.vanillakfc.listeners;

import org.bukkit.Tag;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Nerfs Unbreaking's damage-negation chance so it ramps up more slowly:
 * 100/(1+level) becomes 100/(1+level/3), with level/3 as real division (not
 * floored) - e.g. level 1 = 75% chance to still take damage, level 2 = 60%,
 * level 3 = 50%, tapering off from there instead of vanilla's steeper curve.
 *
 * <p>This applies to every item Unbreaking can go on and every action that
 * costs durability, because it works on the durability event itself rather
 * than on a list of actions. Two earlier designs did not:
 *
 * <ul>
 *   <li>the first marked Unbreaking items vanilla-{@code Unbreakable} to
 *       suppress vanilla's roll, which also suppressed the durability bar and
 *       tooltip, so manually-applied damage was invisible;
 *   <li>the second cancelled vanilla's durability event and re-applied the
 *       loss by hand from block-break, melee, armor-damage and Elytra
 *       handlers. Anything reached by no handler - bows above all, but also
 *       crossbows, tridents, shields, fishing rods, shears, flint and steel
 *       and brushes - had its damage cancelled and nothing put it back, so it
 *       never wore out at all.
 * </ul>
 *
 * <p>What makes a single handler possible is a change of lever, from how
 * often an item is damaged to how much it is damaged when it is. Vanilla
 * rolls Unbreaking internally and only fires {@link PlayerItemDamageEvent}
 * for the damage that survived - a roll that came out zero skips the event
 * entirely, so a listener cannot make damage happen more often than vanilla
 * already decided, which is what forced the per-action rewrite before. It can
 * however change the number it is handed. Scaling that number by
 * {@code (this curve) / (vanilla's curve)} leaves the expected durability
 * loss per use exactly on the intended curve: Unbreaking III on a tool fires
 * at vanilla's 25% and takes 2 points instead of 1, which costs the same over
 * time as the intended 50% chance of 1 point.
 *
 * <p>The trade-off is granularity, not rate. Wear arrives in fewer, larger
 * steps than the percentages above literally describe, and an individual use
 * can differ; an item's total lifetime cannot. Where the multiplier lands
 * below 1 - armor, whose vanilla curve is already shallower than a tool's -
 * a scaled-down roll can come out at zero points, and the event is cancelled
 * rather than passed along as a damage of 0.
 *
 * <p>Vanilla's own rules are all still vanilla's to apply, which is the other
 * half of why this is now one handler: swords costing 2 durability per block
 * broken, armor's damage being a fraction of the hit, an Elytra costing 1 per
 * second of flight, and an item breaking at 0 all happen exactly as they
 * always did, and arrive here as a number to scale.
 */
public final class UnbreakingListener implements Listener {

    /**
     * This plugin's curve: the chance an item still takes durability damage
     * from a use, at a given Unbreaking level.
     */
    private static double chanceToDamage(int level) {
        return 1.0 / (1 + level / 3.0);
    }

    /**
     * Vanilla's curve, read off the {@code minecraft:unbreaking} enchantment
     * definition: a {@code remove_binomial} whose chance to strike each point
     * of damage is {@code level/(level+1)} for everything except armor, and
     * {@code 2*level/(5*level+5)} for armor. Expressed here as the chance a
     * point survives, to line up with {@link #chanceToDamage}.
     *
     * <p>The armor split is decided on the same {@code
     * #minecraft:enchantable/armor} tag vanilla's own definition branches on,
     * so this can't disagree with the roll it is correcting for.
     */
    private static double vanillaChanceToDamage(ItemStack item, int level) {
        if (Tag.ITEMS_ENCHANTABLE_ARMOR.isTagged(item.getType())) {
            return 1.0 - (2.0 * level) / (5.0 * level + 5.0);
        }
        return 1.0 / (level + 1.0);
    }

    // Rounds to a whole number of durability points without losing the
    // fraction: 1.8 comes out as 2 four times in five and 1 the fifth time,
    // so the average stays 1.8. Plain rounding would quietly shift the curve,
    // and every multiplier here has a fractional part at some level.
    private static int roundProbabilistically(double points) {
        int whole = (int) Math.floor(points);
        double fraction = points - whole;
        if (fraction > 0.0 && ThreadLocalRandom.current().nextDouble() < fraction) {
            return whole + 1;
        }
        return whole;
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        int level = meta.getEnchantLevel(Enchantment.UNBREAKING);
        if (level <= 0) return;

        double multiplier = chanceToDamage(level) / vanillaChanceToDamage(item, level);
        int scaled = roundProbabilistically(event.getDamage() * multiplier);
        if (scaled <= 0) {
            event.setCancelled(true);
        } else {
            event.setDamage(scaled);
        }
    }

    // One-time cleanup for items marked Unbreakable by the oldest design -
    // clears the stuck flag so the durability bar/tooltip shows again. Scoped
    // to Unbreaking items specifically so it doesn't touch an unrelated
    // Unbreakable item some other source might have set.
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        PlayerInventory inv = event.getPlayer().getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null) continue;
            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.isUnbreakable() || meta.getEnchantLevel(Enchantment.UNBREAKING) <= 0) continue;

            meta.setUnbreakable(false);
            meta.removeItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            item.setItemMeta(meta);
            inv.setItem(i, item);
        }
    }
}
