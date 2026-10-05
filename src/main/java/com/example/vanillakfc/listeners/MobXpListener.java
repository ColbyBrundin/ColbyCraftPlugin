package com.example.vanillakfc.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Mobs drop 50% more XP when they die.
 *
 * <p>Applies to whatever XP the death is about to drop, so vanilla's own
 * rules for whether a mob drops any at all (killed by a player or tamed
 * wolf recently, not a baby, and so on) still decide that. Players are left
 * out - their death XP is {@link DeathDropListener}'s business.
 *
 * <p>Runs at HIGH so it multiplies the final amount: another listener that
 * zeroes a mob's XP at normal priority (Iron Golems, see
 * {@link IronGolemListener}) stays at zero.
 */
public final class MobXpListener implements Listener {

    private static final double XP_MULTIPLIER = 1.5;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) return;
        int xp = event.getDroppedExp();
        if (xp <= 0) return;
        event.setDroppedExp(roundProbabilistically(xp * XP_MULTIPLIER));
    }

    // Most mobs drop small amounts, where plain rounding would skew the
    // bonus badly - a 1-XP mob would get either nothing extra or double.
    // Rounding up with the fractional part's chance keeps the average at
    // exactly 1.5x: 7.5 is 7 half the time and 8 the other half.
    private static int roundProbabilistically(double xp) {
        int whole = (int) Math.floor(xp);
        double fraction = xp - whole;
        if (fraction > 0.0 && ThreadLocalRandom.current().nextDouble() < fraction) {
            return whole + 1;
        }
        return whole;
    }
}
