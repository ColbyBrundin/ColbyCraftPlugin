package com.example.vanillakfc.listeners;

import com.example.vanillakfc.advancements.Advancements;
import io.papermc.paper.event.player.PlayerBedFailEnterEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.world.ClockTimeSkipEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sleeping through the night streakily gets risky: starting on the 2nd
 * consecutive night slept, each additional night adds another 25% chance
 * (2nd night = 25%, 3rd = 50%, 4th = 75%, 5th = guaranteed) of a 20-minute
 * thunderstorm rolling in right after everyone wakes up. A triggered storm
 * resets the streak back to 0; a night passing without a sleep-triggered skip
 * (nobody slept, or not enough players did) also resets it, since only
 * *consecutive* slept nights count.
 *
 * <p>Tracked per world rather than per player, since a night-skip and its
 * weather are inherently world-wide events, not individual to one sleeper.
 *
 * <p>Also blocks entering a bed at all while thundering (regardless of
 * whether the storm came from this streak mechanic or vanilla's own random
 * weather) - otherwise a player could just sleep through the punishment
 * storm and immediately resume the streak.
 *
 * <p>The streak is reset eagerly, at sunrise after any night nobody slept
 * through, rather than lazily the next time someone sleeps. An earlier
 * version only ever touched the stored streak on a sleep, so a missed night
 * left the old number sitting there: the storm roll still came out right
 * (the next sleep noticed the gap and restarted at 1), but the nightfall
 * announcement read the stale value and reported a streak that no longer
 * existed.
 *
 * <p>Progress is reported as it happens: the streak each nightfall, each
 * increase when a night is slept through, a reset when day breaks on an
 * unslept night, the storm when it rolls in, and the storm passing. Only
 * storms this listener started are announced on ending - a vanilla storm
 * passing says nothing, because it didn't reset anything.
 *
 * <p>Both streak announcements carry the odds the streak is actually buying,
 * so the number isn't left for players to convert in their heads: nightfall
 * quotes what sleeping tonight would risk, and a slept-through night quotes
 * what sleeping again tomorrow would. The latter is omitted when the sleep
 * just triggered a storm, since the streak is back to 0 and the storm message
 * following a tick later says so.
 */
public final class SleepStreakListener implements Listener {

    private static final int THUNDERSTORM_DURATION_TICKS = 20 * 60 * 20; // 20 real-world minutes
    // Derived rather than written into the message, so the two can't drift
    // apart if the duration above is ever retuned.
    private static final int THUNDERSTORM_DURATION_MINUTES = THUNDERSTORM_DURATION_TICKS / (20 * 60);
    private static final double CHANCE_PER_STREAK_STEP = 0.25;
    private static final String VANILLA_NO_SLEEP_KEY = "block.minecraft.bed.no_sleep";

    // Nightfall and sunrise aren't events, so they have to be polled for.
    // Once a second is plenty: each boundary is a single instant and nothing
    // here needs tick-level precision to notice it.
    private static final long DAY_NIGHT_POLL_TICKS = 20L;

    private static final String GAMBLING_ADVANCEMENT = "gambling_addiction";

    private final Plugin plugin;
    private final Advancements advancements;
    private final Map<UUID, Integer> streaks = new HashMap<>();
    // Worlds whose current night has been slept through. Cleared at every
    // nightfall so each night starts unslept, and consumed at sunrise to
    // decide whether the streak survives.
    private final Set<UUID> sleptThisNight = new HashSet<>();
    // Worlds whose current thunderstorm this listener started, so ending one
    // can be told apart from vanilla weather simply running its course.
    private final Set<UUID> pluginStorms = new HashSet<>();
    private final Map<UUID, Boolean> wasDayTime = new HashMap<>();
    private BukkitTask dayNightTask;

    public SleepStreakListener(Plugin plugin, Advancements advancements) {
        this.plugin = plugin;
        this.advancements = advancements;
    }

    public void start() {
        for (World world : Bukkit.getWorlds()) {
            if (WorldClock.hasDayNightCycle(world)) {
                wasDayTime.put(world.getUID(), !WorldClock.isNight(world));
            }
        }
        dayNightTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::checkDayNightEdges, DAY_NIGHT_POLL_TICKS, DAY_NIGHT_POLL_TICKS);
    }

    public void stop() {
        if (dayNightTask != null) {
            dayNightTask.cancel();
            dayNightTask = null;
        }
        wasDayTime.clear();
        pluginStorms.clear();
        sleptThisNight.clear();
    }

    // Seeding from the world's current state on start, rather than assuming
    // daytime, keeps a plugin reload during the night from immediately
    // reporting a nightfall that already happened.
    //
    // Driven by the clock, not World#isDayTime(): that measures sky
    // brightness, so weather moved it - see WorldClock for how rain clearing
    // up at dusk once produced a false sunrise that wiped a streak.
    private void checkDayNightEdges() {
        for (World world : Bukkit.getWorlds()) {
            if (!WorldClock.hasDayNightCycle(world)) {
                continue;
            }
            UUID worldId = world.getUID();
            boolean dayTime = !WorldClock.isNight(world);
            Boolean previous = wasDayTime.put(worldId, dayTime);
            if (previous == null || previous == dayTime) {
                continue;
            }
            if (dayTime) {
                onSunrise(world);
            } else {
                onNightfall(world);
            }
        }
    }

    private void onNightfall(World world) {
        // A fresh night starts unslept. Clearing here as well as at sunrise
        // also discards any flag a daytime skip left behind, so it can't be
        // mistaken for this night being slept through.
        sleptThisNight.remove(world.getUID());

        int streak = streaks.getOrDefault(world.getUID(), 0);
        String tonight = formatPercent(stormChanceAfterSleeping(streak));
        plugin.getLogger().info("Night fell in '" + world.getName() + "' - sleep streak: " + streak
                + " (sleeping tonight: " + tonight + " thunderstorm chance).");
        broadcast(world, "§9Night falls. Current sleep streak: " + streak
                + ". Sleeping tonight: " + tonight + " chance of a thunderstorm.");
    }

    // A sleep-skip jumps the clock straight to morning, so it arrives here as
    // an ordinary sunrise too - the flag set by onTimeSkip is what tells the
    // two apart.
    private void onSunrise(World world) {
        UUID worldId = world.getUID();
        if (sleptThisNight.remove(worldId)) {
            return;
        }
        Integer previous = streaks.put(worldId, 0);
        // Only a streak that actually existed is worth announcing; a reset
        // from 0 to 0 would just be a daily line of noise.
        if (previous == null || previous == 0) {
            return;
        }
        plugin.getLogger().info("Day broke in '" + world.getName() + "' without the night being slept through"
                + " - sleep streak reset to 0 (was " + previous + ").");
        broadcast(world, "§9The night passed without sleep. Sleep streak reset to 0.");
    }

    // Fires when a world's thunder state flips. Only a plugin-started storm
    // ending is worth reporting, and only once - remove() both tests and
    // clears the marker so a later vanilla storm stays silent.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThunderChange(ThunderChangeEvent event) {
        if (event.toThunderState()) {
            return;
        }
        World world = event.getWorld();
        if (!pluginStorms.remove(world.getUID())) {
            return;
        }
        plugin.getLogger().info("Plugin thunderstorm ended in '" + world.getName()
                + "' - sleep streak reset to 0.");
        broadcast(world, "§9The thunderstorm has passed. Sleep streak reset to 0.");
    }

    // The odds a sleep carries, given the streak standing *before* that sleep:
    // the sleep itself takes the streak to currentStreak + 1, and the roll is
    // one step per night beyond the first. Serves both announcements - at
    // nightfall the stored streak is what tonight's sleep would build on, and
    // after a sleep the new streak is what tomorrow's would.
    private static double stormChanceAfterSleeping(int currentStreak) {
        return Math.min(1.0, currentStreak * CHANCE_PER_STREAK_STEP);
    }

    // Every reachable chance is a whole percent while the step is a quarter,
    // so rounding here only ever tidies floating-point dust.
    private static String formatPercent(double chance) {
        return Math.round(chance * 100.0) + "%";
    }

    private static void broadcast(World world, String message) {
        for (Player player : world.getPlayers()) {
            player.sendMessage(message);
        }
    }

    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        World world = event.getPlayer().getWorld();

        // Temporary diagnostic: confirms 26.3's straw beds route through this
        // same event (they have no dedicated one), so the thunderstorm block
        // below covers them without any straw-bed-specific code. Remove once
        // STRAW_BED has been observed here.
        plugin.getLogger().info("Bed enter: " + event.getBed().getType()
                + " thundering=" + world.isThundering() + " storm=" + world.hasStorm());

        // Requires both flags, for the same reason LightningListener does:
        // isThundering() can sit true on its own after a weather transition,
        // with no rain and no darkened sky, and blocking sleep on that alone
        // looks to players like the plugin inventing a storm that isn't there.
        if (!world.isThundering() || !world.hasStorm()) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage("§cYou can't sleep through a thunderstorm.");
    }

    // Vanilla's refusal reads "You can sleep only at night or during
    // thunderstorms", which this plugin makes untrue - onBedEnter above
    // refuses every thunderstorm. Swap it for the rule as it actually stands.
    // Matched on the translation key rather than the fail reason so only this
    // exact message changes; since 26.3 it comes from the dimension's bed
    // rule, which regular and straw beds both share.
    @EventHandler(ignoreCancelled = true)
    public void onBedFail(PlayerBedFailEnterEvent event) {
        if (event.getMessage() instanceof TranslatableComponent translatable
                && translatable.key().equals(VANILLA_NO_SLEEP_KEY)) {
            event.setMessage(Component.text("You can sleep only at night"));
        }
    }

    // MONITOR + ignoreCancelled: a skip another plugin cancelled never
    // actually skipped the night, so it mustn't count toward the streak.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTimeSkip(TimeSkipEvent event) {
        if (event.getSkipReason() != ClockTimeSkipEvent.SkipReason.NIGHT_SKIP) {
            return;
        }

        World world = event.getWorld();
        UUID worldId = world.getUID();

        // No day arithmetic needed any more: an unslept night has already
        // zeroed the streak at sunrise, so whatever is stored is by
        // definition the run of consecutive nights leading up to this one.
        int streak = streaks.getOrDefault(worldId, 0) + 1;
        streaks.put(worldId, streak);
        sleptThisNight.add(worldId);

        // Rolled before anything is announced, because the roll decides what
        // the announcement may promise: a storm puts the streak back to 0, so
        // quoting tomorrow's odds off the streak just earned would be a lie by
        // the time the storm message lands a tick later.
        double chance = stormChanceAfterSleeping(streak - 1);
        boolean stormTriggered = chance > 0.0 && ThreadLocalRandom.current().nextDouble() < chance;

        // "Gambling Addiction": went to bed knowing the storm was certain.
        // Vanilla wakes everyone only after this event, so whoever is still
        // in bed right now is exactly who slept the night away.
        if (chance >= 1.0) {
            for (Player player : world.getPlayers()) {
                if (player.isSleeping()) {
                    advancements.award(player, GAMBLING_ADVANCEMENT);
                }
            }
        }

        plugin.getLogger().info("Night slept through in '" + world.getName() + "' - sleep streak increased to "
                + streak + " (storm chance was " + formatPercent(chance) + ", "
                + (stormTriggered ? "rolled" : "missed") + ").");
        String announcement = "§9You slept through the night. Sleep streak increased to " + streak + ".";
        if (!stormTriggered) {
            announcement += " Sleeping again tomorrow night: "
                    + formatPercent(stormChanceAfterSleeping(streak)) + " chance of a thunderstorm.";
        }
        broadcast(world, announcement);

        if (!stormTriggered) {
            return;
        }

        streaks.put(worldId, 0);
        // Vanilla clears weather to clear skies as part of the same
        // sleep-skip sequence this event fires from; applying the
        // thunderstorm next tick instead of inline avoids that reset
        // immediately undoing it.
        Bukkit.getScheduler().runTask(plugin, () -> {
            world.setStorm(true);
            world.setWeatherDuration(THUNDERSTORM_DURATION_TICKS);
            world.setThundering(true);
            world.setThunderDuration(THUNDERSTORM_DURATION_TICKS);

            // Marked only once the storm has actually been applied, so a
            // storm that never started can't later announce a reset.
            pluginStorms.add(worldId);

            // Announced from inside the same task as the weather change, so
            // the message can never claim a storm that failed to start. Sent
            // only to this world, matching where the storm actually is.
            plugin.getLogger().info("Sleep-triggered thunderstorm started in '" + world.getName()
                    + "' for " + THUNDERSTORM_DURATION_MINUTES + " minutes; sleep streak reset to 0.");
            broadcast(world, "§9Sleeping again has brought on a thunderstorm - it will last "
                    + THUNDERSTORM_DURATION_MINUTES + " minutes.");
        });
    }
}
