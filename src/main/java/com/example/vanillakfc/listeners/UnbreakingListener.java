package com.example.vanillakfc.listeners;

import com.example.vanillakfc.UnbreakingDatapack;
import io.papermc.paper.event.entity.EntityDamageItemEvent;
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
 * <p>There are two ways it can get there, and which one is running depends on
 * whether {@link UnbreakingDatapack} reports the bundled datapack in force.
 *
 * <p><b>Direct</b>, with the pack on. The pack strips vanilla's own
 * {@code item_damage} effect from {@code minecraft:unbreaking}, so vanilla
 * reduces nothing and {@link PlayerItemDamageEvent} fires on every single use.
 * Each point of damage is then kept with probability {@link #chanceToDamage},
 * which is the curve applied literally - Unbreaking II really is a 60% chance
 * of taking the point. This is the accurate path and the one the plugin ships
 * for.
 *
 * <p><b>Scaled</b>, with the pack off, as a fallback. Vanilla rolls Unbreaking
 * internally and only raises the event for damage that survived - a roll of
 * zero skips the event entirely - so a listener cannot make damage land more
 * often than vanilla decided, only change the number it is handed. Multiplying
 * that number by {@code (this curve) / (vanilla's curve)} puts the expected
 * durability loss per use on the same curve by a different route: Unbreaking
 * III fires at vanilla's 25% and takes 2 points rather than 1.
 *
 * <p>Both land on the same average. They differ in spread, which is the whole
 * reason the datapack exists: the scaled path inherits vanilla's coin flip and
 * amplifies it by the multiplier, giving roughly three times the variance. A
 * golden pickaxe with Unbreaking II averages ~54 blocks either way, but the
 * middle 95% of them runs 43-66 on the direct path against 35-78 on the scaled
 * one. Low-durability tools feel that most, having the fewest rolls to average
 * out.
 *
 * <p>Getting the two backwards would not be subtle - applying the curve on top
 * of a reduction vanilla already made would roughly double an item's intended
 * life - so the check is made per event against the pack's live enabled flag
 * rather than cached at startup.
 *
 * <p>Vanilla's own rules are all still vanilla's to apply, which is the other
 * half of why this is so little code: swords costing 2 durability per block
 * broken, armor's damage being a fraction of the hit, an Elytra costing 1 per
 * second of flight, and an item breaking at 0 all happen exactly as they
 * always did, and arrive here as a number to adjust.
 *
 * <p>Mob-held gear goes through {@link EntityDamageItemEvent} rather than the
 * player event, and is handled identically. It matters more than it used to:
 * with the datapack on, vanilla's Unbreaking does nothing for anyone, so
 * leaving mobs uncovered would mean an Unbreaking tool on a zombie wearing out
 * at the unenchanted rate.
 */
public final class UnbreakingListener implements Listener {

    /** Sentinel for "this item isn't ours to touch", distinct from a real 0. */
    private static final int UNCHANGED = Integer.MIN_VALUE;

    /**
     * This plugin's curve: the chance an item still takes durability damage
     * from a use, at a given Unbreaking level.
     */
    static double chanceToDamage(int level) {
        return 1.0 / (1 + level / 3.0);
    }

    /**
     * Vanilla's curve, read off the {@code minecraft:unbreaking} enchantment
     * definition: a {@code remove_binomial} whose chance to strike each point
     * of damage is {@code level/(level+1)} for everything except armor, and
     * {@code 2*level/(5*level+5)} for armor. Expressed here as the chance a
     * point survives, to line up with {@link #chanceToDamage}.
     *
     * <p>Which branch applies is decided by {@link #isArmor}, on the same
     * {@code #minecraft:enchantable/armor} tag vanilla's own definition uses,
     * so this can't disagree with the roll it is correcting for.
     */
    static double vanillaChanceToDamage(boolean armor, int level) {
        if (armor) {
            return 1.0 - (2.0 * level) / (5.0 * level + 5.0);
        }
        return 1.0 / (level + 1.0);
    }

    private static boolean isArmor(ItemStack item) {
        return Tag.ITEMS_ENCHANTABLE_ARMOR.isTagged(item.getType());
    }

    /**
     * How much to multiply a durability loss by to move it off vanilla's
     * curve and onto this one.
     */
    static double damageMultiplier(boolean armor, int level) {
        return chanceToDamage(level) / vanillaChanceToDamage(armor, level);
    }

    /**
     * The scaled fallback: what to apply when vanilla has already taken its own
     * cut. A result of 0 means the damage should be dropped entirely.
     */
    static int scaledDamage(int damage, boolean armor, int level) {
        return roundProbabilistically(damage * damageMultiplier(armor, level));
    }

    /**
     * The direct path: vanilla reduced nothing, so every point of damage is put
     * through this plugin's curve here. Point by point rather than as one roll
     * over the whole amount, which is the shape vanilla's own
     * {@code remove_binomial} has and what keeps a 2-point sword break behaving
     * like two independent uses.
     */
    static int rolledDamage(int damage, int level) {
        double keep = chanceToDamage(level);
        int kept = 0;
        for (int point = 0; point < damage; point++) {
            if (ThreadLocalRandom.current().nextDouble() < keep) {
                kept++;
            }
        }
        return kept;
    }

    /**
     * The whole computation this listener performs, split out from the events
     * so it can be exercised directly - see {@code UnbreakingCurveCheck} in the
     * {@code harness} source set, which runs both paths against the intended
     * curve. A result of 0 means the damage should be dropped entirely.
     */
    static int adjustedDamage(int damage, boolean armor, int level, boolean datapackActive) {
        return datapackActive ? rolledDamage(damage, level) : scaledDamage(damage, armor, level);
    }

    // Rounds to a whole number of durability points without losing the
    // fraction: 1.8 comes out as 2 four times in five and 1 the fifth time,
    // so the average stays 1.8. Plain rounding would quietly shift the curve,
    // and every multiplier here has a fractional part at some level.
    static int roundProbabilistically(double points) {
        int whole = (int) Math.floor(points);
        double fraction = points - whole;
        if (fraction > 0.0 && ThreadLocalRandom.current().nextDouble() < fraction) {
            return whole + 1;
        }
        return whole;
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        int adjusted = adjust(event.getItem(), event.getDamage());
        if (adjusted == UNCHANGED) return;
        if (adjusted <= 0) {
            event.setCancelled(true);
        } else {
            event.setDamage(adjusted);
        }
    }

    // Gear on a mob wears out through its own event, and gets the same
    // treatment - with the datapack on, vanilla's Unbreaking does nothing for
    // anyone, so an uncovered mob's tool would wear at the unenchanted rate.
    @EventHandler(ignoreCancelled = true)
    public void onMobItemDamage(EntityDamageItemEvent event) {
        int adjusted = adjust(event.getItem(), event.getDamage());
        if (adjusted == UNCHANGED) return;
        if (adjusted <= 0) {
            event.setCancelled(true);
        } else {
            event.setDamage(adjusted);
        }
    }

    /** {@link #UNCHANGED} when the item has no Unbreaking and nothing applies. */
    private int adjust(ItemStack item, int damage) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return UNCHANGED;

        int level = meta.getEnchantLevel(Enchantment.UNBREAKING);
        if (level <= 0) return UNCHANGED;

        return adjustedDamage(damage, isArmor(item), level, UnbreakingDatapack.isActive());
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
