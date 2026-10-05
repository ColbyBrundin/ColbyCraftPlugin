package com.example.vanillakfc.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemMendEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Caps how much raw XP (points, not levels) each player can gain within a
 * fixed, server-wide 5-minute window, so grinding a single source can't be
 * used to farm unlimited XP. Windows are aligned to wall-clock time
 * (System.currentTimeMillis() / WINDOW_MILLIS) rather than a per-player
 * rolling timer, so every player shares the exact same reset instant and
 * the design needs no persistence - a restart just recomputes the current
 * window from the clock.
 *
 * <p>Hooks {@link PlayerExpChangeEvent}, the one event covering vanilla
 * point-based XP grants (mob kills, orb pickup, mining, breeding, fishing,
 * furnace pickup, villager trading). It has no "reason" to tell natural
 * play apart from anything else routed through the same internal path, so
 * the cap applies uniformly to any positive amount. Level grants (/xp add
 * ... levels, Player#giveExpLevels) fire PlayerLevelChangeEvent instead and
 * never reach this listener.
 *
 * <p>Mending draws from the same budget. An orb's XP goes to repairing
 * Mending gear first, and only what's left over reaches
 * {@link PlayerExpChangeEvent} - so before {@link #onItemMend} existed, XP
 * spent on repairs never counted and a player at the cap could keep mending
 * indefinitely. Each repair now counts the XP it consumes, is cut down to
 * what the budget still allows, and is cancelled outright once it's spent.
 * A cancelled repair hands the orb's XP on to the player as usual, where the
 * cap then zeroes it, so the orb is still used up.
 */
public final class XpCapListener implements Listener {

    private static final long WINDOW_MILLIS = 5 * 60 * 1000L;
    private static final int XP_CAP = 500;

    private static final class WindowState {
        long windowIndex;
        int xpGained;
        boolean warned;
    }

    // Intentionally never pruned (e.g. on quit): each entry is a handful of
    // bytes, and removing it on quit would let a capped player relog to
    // reset their own counter early, defeating the cap.
    private final Map<UUID, WindowState> windowStates = new HashMap<>();

    @EventHandler
    public void onExpChange(PlayerExpChangeEvent event) {
        if (event.getAmount() <= 0) {
            return;
        }

        Player player = event.getPlayer();
        WindowState state = currentState(player);

        int remaining = XP_CAP - state.xpGained;
        if (remaining <= 0) {
            event.setAmount(0);
        } else if (event.getAmount() > remaining) {
            event.setAmount(remaining);
            state.xpGained = XP_CAP;
        } else {
            state.xpGained += event.getAmount();
        }

        warnIfCapped(player, state);
    }

    // HIGHEST so the repair amount settled here is the final one - the XP
    // counted below is exactly what the repair goes on to consume.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemMend(PlayerItemMendEvent event) {
        int consumed = event.getConsumedExperience();
        if (consumed <= 0) {
            return;
        }

        Player player = event.getPlayer();
        WindowState state = currentState(player);
        int remaining = XP_CAP - state.xpGained;

        if (consumed > remaining) {
            // Cut the repair down in proportion. Mending's durability-per-XP
            // comes from enchantment effects Paper won't expose (the event's
            // getDurabilityToXpOperation() just throws since 1.21), and
            // getConsumedExperience() is fixed when the event is made, so the
            // event's own repair-to-XP ratio is the only rate there is to go
            // on. The server charges the orb for the reduced repair at that
            // same rate, so this lands on the remaining budget, give or take
            // integer rounding.
            int repair = (int) ((long) event.getRepairAmount() * remaining / consumed);
            if (repair <= 0) {
                event.setCancelled(true);
            } else {
                event.setRepairAmount(repair);
            }
            // Either way, the whole remaining budget is gone.
            state.xpGained = XP_CAP;
            warnIfCapped(player, state);
            return;
        }

        state.xpGained += consumed;
        warnIfCapped(player, state);
    }

    private WindowState currentState(Player player) {
        long window = currentWindowIndex();
        WindowState state = windowStates.computeIfAbsent(player.getUniqueId(), id -> new WindowState());
        if (state.windowIndex != window) {
            state.windowIndex = window;
            state.xpGained = 0;
            state.warned = false;
        }
        return state;
    }

    private static void warnIfCapped(Player player, WindowState state) {
        if (state.xpGained >= XP_CAP && !state.warned) {
            state.warned = true;
            player.sendMessage("§cYou've hit the " + XP_CAP
                    + " XP cap for this 5-minute period. Resets in " + formatRemaining(state.windowIndex) + ".");
        }
    }

    private static long currentWindowIndex() {
        return System.currentTimeMillis() / WINDOW_MILLIS;
    }

    private static String formatRemaining(long windowIndex) {
        long remainingMillis = Math.max(0, (windowIndex + 1) * WINDOW_MILLIS - System.currentTimeMillis());
        long seconds = remainingMillis / 1000;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }
}
