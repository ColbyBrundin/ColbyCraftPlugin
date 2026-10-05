package com.example.vanillakfc.listeners;

import com.example.vanillakfc.advancements.Advancements;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;

/**
 * Awards the "Dying Grace" advancement at {@link #DEATHS_REQUIRED} deaths.
 *
 * <p>The advancement itself is in the bundled datapack and has nothing but an
 * {@code minecraft:impossible} criterion, which is the standard way to hand a
 * plugin control of when it is earned - vanilla's own triggers can count
 * deaths in a session but not a running total.
 *
 * <p>The count comes from the server's own scoreboard wherever possible: any
 * objective tracking {@code deathCount} is what players are already reading
 * off a sidebar, so the advancement agrees with the number they can see. The
 * highest such objective wins if there are several, so one added later than
 * another can't undercount. With no death objective on the scoreboard at all,
 * it falls back to the player's own {@code DEATHS} statistic, which vanilla
 * always tracks.
 *
 * <p>Checked a tick after death rather than during it, because vanilla
 * increments the scoreboard after {@link PlayerDeathEvent} has finished - read
 * inside the event, the score is still one behind. Also checked on join, so
 * anyone already past the mark before this existed earns it on their way in
 * rather than having to die once more for it.
 */
public final class DeathMilestoneListener implements Listener {

    private static final int DEATHS_REQUIRED = 40;
    private static final String ADVANCEMENT = "dying_grace";
    /** Vanilla's own criterion name for a scoreboard death counter. */
    private static final String DEATH_COUNT_CRITERION = "deathCount";

    private final Plugin plugin;
    private final Advancements advancements;
    private boolean loggedSource;

    public DeathMilestoneListener(Plugin plugin, Advancements advancements) {
        this.plugin = plugin;
        this.advancements = advancements;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        checkNextTick(event.getEntity());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        checkNextTick(event.getPlayer());
    }

    private void checkNextTick(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                check(player);
            }
        });
    }

    private void check(Player player) {
        if (deathCount(player) < DEATHS_REQUIRED) return;
        advancements.award(player, ADVANCEMENT);
    }

    private int deathCount(Player player) {
        int fromScoreboard = -1;
        String source = null;
        for (Objective objective : Bukkit.getScoreboardManager().getMainScoreboard().getObjectives()) {
            // Matched on the criterion, not the objective's name - a server's
            // death counter can be called anything ("deaths", "Deaths",
            // "total_deaths"); what makes it a death counter is deathCount.
            // Compared by the criterion's name rather than the Criteria object,
            // so this doesn't depend on the server handing back the same
            // cached instance as the constant.
            Criteria criteria = objective.getTrackedCriteria();
            if (criteria == null || !DEATH_COUNT_CRITERION.equals(criteria.getName())) continue;
            Score score = objective.getScore(player);
            if (score.isScoreSet() && score.getScore() > fromScoreboard) {
                fromScoreboard = score.getScore();
                source = "scoreboard objective '" + objective.getName() + "'";
            }
        }

        int deaths = fromScoreboard >= 0 ? fromScoreboard : player.getStatistic(Statistic.DEATHS);
        if (!loggedSource) {
            loggedSource = true;
            plugin.getLogger().info("Dying Grace counts deaths from "
                    + (source != null ? source : "the DEATHS statistic (no deathCount objective on the scoreboard)")
                    + "; " + player.getName() + " is on " + deaths + " of " + DEATHS_REQUIRED + ".");
        }
        return deaths;
    }
}
