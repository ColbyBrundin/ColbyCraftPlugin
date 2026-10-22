package com.example.vanillakfc.listeners;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Mobs drop 50% more XP when they die, and more again for a kill made with
 * a Wisdom weapon.
 *
 * <p>Applies to whatever XP the death is about to drop, so vanilla's own
 * rules for whether a mob drops any at all (killed by a player or tamed
 * wolf recently, not a baby, and so on) still decide that. Players are left
 * out - their death XP is {@link DeathDropListener}'s business.
 *
 * <p><b>Wisdom</b> ({@code vanillakfc:wisdom}, defined in the bundled
 * datapack) multiplies on top of the 1.5x rather than adding to it: +15% /
 * +30% / +50% at I / II / III, so 1.725x / 1.95x / 2.25x in all. It's read
 * from the main hand of the player credited with the kill, at the moment
 * of death - the same slot Looting works from, so it counts for a bow kill
 * only if the Wisdom weapon is in the main hand. A level above III (from a
 * command) counts as III.
 *
 * <p>Runs at HIGH so it multiplies the final amount: another listener that
 * zeroes a mob's XP at normal priority (Iron Golems, see
 * {@link IronGolemListener}) stays at zero.
 */
public final class MobXpListener implements Listener {

    // 1.5x, kept as a fraction - see boostedXp.
    private static final int XP_MULTIPLIER_NUMERATOR = 3;
    private static final int XP_MULTIPLIER_DENOMINATOR = 2;
    // Indexed by Wisdom level.
    private static final int[] WISDOM_BONUS_PERCENT = {0, 15, 30, 50};
    private static final NamespacedKey WISDOM_KEY = NamespacedKey.fromString("vanillakfc:wisdom");

    // Looked up on first use rather than at construction, and null if the
    // datapack isn't loaded - then there's simply no Wisdom bonus.
    private Enchantment wisdom;
    private boolean wisdomLookedUp;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) return;
        int xp = event.getDroppedExp();
        if (xp <= 0) return;
        event.setDroppedExp(roundProbabilistically(boostedXp(xp, wisdomLevel(event.getEntity().getKiller()))));
    }

    private int wisdomLevel(Player killer) {
        if (killer == null) return 0;
        if (!wisdomLookedUp) {
            wisdom = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(WISDOM_KEY);
            wisdomLookedUp = true;
        }
        if (wisdom == null) return 0;
        return killer.getInventory().getItemInMainHand().getEnchantmentLevel(wisdom);
    }

    // The exact boosted amount for a kill at this Wisdom level (0 for none).
    // Worked out in whole numbers and divided once at the end, so a result
    // that should be whole is exactly whole: as doubles, 1.5 x 1.15 is
    // 1.72499..., and 40 XP would come out as 68.999... instead of 69.
    // Package-private for MobXpCheck.
    static double boostedXp(int xp, int wisdomLevel) {
        int level = Math.clamp(wisdomLevel, 0, WISDOM_BONUS_PERCENT.length - 1);
        long numerator = (long) xp * XP_MULTIPLIER_NUMERATOR * (100 + WISDOM_BONUS_PERCENT[level]);
        return numerator / (100.0 * XP_MULTIPLIER_DENOMINATOR);
    }

    // Most mobs drop small amounts, where plain rounding would skew the
    // bonus badly - a 1-XP mob would get either nothing extra or double.
    // Rounding up with the fractional part's chance keeps the average at
    // exactly the multiplier: 7.5 is 7 half the time and 8 the other half.
    // Package-private for MobXpCheck.
    static int roundProbabilistically(double xp) {
        int whole = (int) Math.floor(xp);
        double fraction = xp - whole;
        if (fraction > 0.0 && ThreadLocalRandom.current().nextDouble() < fraction) {
            return whole + 1;
        }
        return whole;
    }
}
