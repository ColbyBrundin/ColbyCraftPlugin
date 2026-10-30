package com.example.vanillakfc.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Caps how much raw XP (points, not levels) each player can gain within a
 * fixed, server-wide 10-minute window, so grinding a single source can't be
 * used to farm unlimited XP. Windows are aligned to wall-clock time
 * (System.currentTimeMillis() / WINDOW_MILLIS) rather than a per-player
 * rolling timer, so every player shares the exact same reset instant and
 * the design needs no persistence - a restart just recomputes the current
 * window from the clock.
 *
 * <p>Hooks {@link PlayerExpChangeEvent}, which the server fires from exactly
 * one place: a player picking up an XP orb. That is every vanilla source of
 * XP points (mob kills, mining, breeding, fishing, furnaces, trading and
 * bottles all hand theirs over as orbs), and nothing else - /xp, advancement
 * rewards and level grants never reach this listener. It has no "reason" to
 * tell one orb from another, so the cap applies uniformly to any positive
 * amount.
 *
 * <p>Mending draws from the same budget. An orb's XP goes to repairing
 * Mending gear first, and only what's left over reaches
 * {@link PlayerExpChangeEvent} - so before {@link #onItemMend} existed, XP
 * spent on repairs never counted and a player at the cap could keep mending
 * indefinitely. Each repair now counts the XP it consumes, is cut down to
 * what the budget still allows, and is cancelled outright once it's spent.
 * A cancelled repair hands the orb's XP on to the player as usual, where the
 * cap then zeroes it, so the orb is still used up. Each point of an orb is
 * counted once: by the repair, or by the pickup, never both.
 *
 * <p><b>Telling the player.</b> Hitting the cap sends a chat message and logs
 * to the console, once per player per window. That alone left the cap silent
 * for the rest of the window - up to ten minutes of orbs vanishing with
 * nothing to say why, and the same again after a relog - so every pickup or
 * repair the cap swallows also shows a line above the hotbar with the time
 * left (at most once a second), and a capped player who rejoins is told
 * again in chat. When the window resets, every online player who had hit
 * the cap in it is told in chat that XP counts again - checked once a
 * second, as the reset is set by the clock rather than by anything the
 * server does.
 *
 * <p><b>The first Ender Dragon.</b> The first dragon killed in an End drops
 * 12,000 XP (18,000 with {@link MobXpListener}), all of which the cap would
 * otherwise throw away past the first 1,000. So the cap is lifted for players
 * in that End from the killing blow until {@link #DRAGON_GRACE_TICKS} after
 * the dragon's body is gone. Timed from the body going, not the blow: the
 * dragon flies to the portal and plays a 10-second death animation first,
 * and only releases the XP over the last 2.5 seconds of it, so a minute
 * counted from the blow would be partly over before any XP existed. XP gained
 * while lifted isn't counted, so the player's window is as they left it.
 * Later dragons (500 XP) are capped as usual.
 */
public final class XpCapListener implements Listener {

    // Was 500 per 5 minutes until 2026-10-09.
    private static final int WINDOW_MINUTES = 10;
    private static final long WINDOW_MILLIS = WINDOW_MINUTES * 60 * 1000L;
    private static final int XP_CAP = 1000;

    private static final long NOTICE_INTERVAL_MILLIS = 1000L;
    private static final long RESET_CHECK_TICKS = 20L;

    private static final long DRAGON_GRACE_TICKS = 60L * 20L;
    // If the dragon's removal is somehow never seen (the fight's chunks
    // unloading mid-death, say), the lift still ends.
    private static final long DRAGON_LIFT_MAX_TICKS = 5L * 60L * 20L;

    private static final class WindowState {
        long windowIndex;
        int xpGained;
        boolean warned;
        long lastNoticeMillis;
    }

    // An End world's cap lift for its first dragon.
    private static final class DragonLift {
        final UUID dragonId;
        int endsAtTick;
        boolean bodyGone;

        DragonLift(UUID dragonId, int endsAtTick) {
            this.dragonId = dragonId;
            this.endsAtTick = endsAtTick;
        }
    }

    // Intentionally never pruned (e.g. on quit): each entry is a handful of
    // bytes, and removing it on quit would let a capped player relog to
    // reset their own counter early, defeating the cap.
    private final Map<UUID, WindowState> windowStates = new HashMap<>();
    // By world UID.
    private final Map<UUID, DragonLift> dragonLifts = new HashMap<>();

    private final Plugin plugin;
    private long lastWindowIndex = currentWindowIndex();
    private BukkitTask resetTask;

    public XpCapListener(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        resetTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkWindowReset, RESET_CHECK_TICKS, RESET_CHECK_TICKS);
    }

    public void stop() {
        if (resetTask != null) {
            resetTask.cancel();
            resetTask = null;
        }
    }

    // Only players who hit the cap in the window that just ended - anyone else
    // was never held back, so has nothing to be told. A capped player who was
    // offline for the reset just finds the cap gone when they're back.
    private void checkWindowReset() {
        long window = currentWindowIndex();
        if (window == lastWindowIndex) return;
        long ended = lastWindowIndex;
        lastWindowIndex = window;

        for (Player player : Bukkit.getOnlinePlayers()) {
            WindowState state = windowStates.get(player.getUniqueId());
            if (state == null || state.windowIndex != ended || state.xpGained < XP_CAP) continue;
            player.sendMessage(Component.text(
                    "Your XP cap has reset - you can gain XP again (up to " + XP_CAP + " in the next "
                            + WINDOW_MINUTES + " minutes).", NamedTextColor.GREEN));
        }
    }

    @EventHandler
    public void onExpChange(PlayerExpChangeEvent event) {
        if (event.getAmount() <= 0) {
            return;
        }

        Player player = event.getPlayer();
        if (capLifted(player)) {
            return;
        }
        WindowState state = currentState(player);

        int remaining = XP_CAP - state.xpGained;
        if (remaining <= 0) {
            event.setAmount(0);
        } else if (event.getAmount() > remaining) {
            event.setAmount(remaining);
            state.xpGained = XP_CAP;
        } else {
            state.xpGained += event.getAmount();
            if (state.xpGained < XP_CAP) {
                return;
            }
        }

        tellCapped(player, state);
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
        if (capLifted(player)) {
            return;
        }
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
            tellCapped(player, state);
            return;
        }

        state.xpGained += consumed;
        if (state.xpGained >= XP_CAP) {
            tellCapped(player, state);
        }
    }

    // A player who was told, left and came back has no way to know the cap
    // is still on them: the once-per-window chat message has been and gone.
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        WindowState state = currentState(player);
        if (state.xpGained >= XP_CAP && !capLifted(player)) {
            player.sendMessage(capMessage(state));
        }
    }

    /**
     * Lifts the cap in an End world when its first dragon takes the killing
     * blow. "First" is the fight's own flag, read here because it is still
     * false at this point - the fight only sets it once the death animation
     * has finished.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragonKilled(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) return;
        World world = dragon.getWorld();
        DragonBattle battle = world.getEnderDragonBattle();
        if (battle == null || battle.hasBeenPreviouslyKilled()) return;

        int now = Bukkit.getCurrentTick();
        DragonLift lift = new DragonLift(dragon.getUniqueId(), now + (int) DRAGON_LIFT_MAX_TICKS);
        dragonLifts.put(world.getUID(), lift);
        scheduleLiftEnd(world, lift, DRAGON_LIFT_MAX_TICKS);

        announce(world, Component.text("The XP cap is lifted here while you collect the dragon's XP.", NamedTextColor.GREEN));
        plugin.getLogger().info("First Ender Dragon killed in '" + world.getName() + "': XP cap lifted there until "
                + (DRAGON_GRACE_TICKS / 20) + "s after its body is gone.");
    }

    // The body going is the end of the death animation, and so of the XP
    // coming out - the grace period is counted from here.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragonRemoved(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) return;
        World world = dragon.getWorld();
        DragonLift lift = dragonLifts.get(world.getUID());
        if (lift == null || lift.bodyGone || !lift.dragonId.equals(dragon.getUniqueId())) return;

        lift.bodyGone = true;
        lift.endsAtTick = Bukkit.getCurrentTick() + (int) DRAGON_GRACE_TICKS;
        scheduleLiftEnd(world, lift, DRAGON_GRACE_TICKS);
        announce(world, Component.text("The XP cap returns in " + (DRAGON_GRACE_TICKS / 20) + " seconds.", NamedTextColor.YELLOW));
    }

    // Fires at whichever end time was current when it was scheduled; if the
    // lift's end has since moved (the safety limit replaced by the real grace
    // period), this one is stale and the later one does the work.
    private void scheduleLiftEnd(World world, DragonLift lift, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (dragonLifts.get(world.getUID()) != lift || Bukkit.getCurrentTick() < lift.endsAtTick) return;
            dragonLifts.remove(world.getUID());
            announce(world, Component.text("The XP cap is back in effect.", NamedTextColor.YELLOW));
            plugin.getLogger().info("XP cap back in effect in '" + world.getName() + "'.");
        }, delayTicks);
    }

    private boolean capLifted(Player player) {
        DragonLift lift = dragonLifts.get(player.getWorld().getUID());
        return lift != null && Bukkit.getCurrentTick() < lift.endsAtTick;
    }

    private static void announce(World world, Component message) {
        for (Player player : world.getPlayers()) {
            player.sendMessage(message);
        }
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

    /**
     * Called every time the cap takes XP from a player or has just been
     * reached. The first time in a window goes to chat and the console; every
     * time after that - each orb or repair swallowed while capped - shows
     * above the hotbar instead, so it is never silent and never spams chat.
     */
    private void tellCapped(Player player, WindowState state) {
        if (!state.warned) {
            state.warned = true;
            player.sendMessage(capMessage(state));
            plugin.getLogger().info(player.getName() + " hit the " + XP_CAP + " XP cap; window resets in "
                    + formatRemaining(state.windowIndex) + ".");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - state.lastNoticeMillis < NOTICE_INTERVAL_MILLIS) return;
        state.lastNoticeMillis = now;
        player.sendActionBar(Component.text(
                "XP cap reached - resets in " + formatRemaining(state.windowIndex), NamedTextColor.RED));
    }

    private static String capMessage(WindowState state) {
        return "§cYou've hit the " + XP_CAP + " XP cap for this " + WINDOW_MINUTES
                + "-minute period. XP you pick up before it resets is lost. Resets in "
                + formatRemaining(state.windowIndex) + ".";
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
