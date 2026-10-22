package com.example.vanillakfc.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.boss.DragonBattle;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Three changes to the Ender Dragon fight.
 *
 * <p><b>Crystals come back.</b> Every {@link #RESPAWN_INTERVAL_TICKS} while a
 * dragon is alive, if any obsidian pillar's crystal is gone, one of the empty
 * pillars - picked at random if there are several - gets a fresh crystal on
 * its bedrock, exactly where the fight first put one. The dragon heals from
 * it as from any other.
 *
 * <p>The pillars stand where vanilla's end spike feature puts them: ten,
 * evenly spaced on a circle of radius 42 around the island's centre. Their
 * heights are shuffled per world seed, so each one's top is found by
 * scanning its column for the bedrock capping the obsidian, then remembered.
 * A pillar whose chunk isn't loaded is left alone rather than loaded.
 *
 * <p><b>Breaking a crystal angers an enderman.</b> A player who destroys an
 * End Crystal - by hitting it or with their own projectile - has one random
 * enderman within {@link #ENDERMAN_RANGE} blocks turn on them, and only them.
 * Targeting it at the player is enough: vanilla turns that target into its
 * own anger at that player, as if they'd been stared at.
 *
 * <p><b>An arrival sound.</b> A player who loads into the End while a dragon
 * is alive there hears a custom clip ({@code vanillakfc:end_dragon_alive}),
 * played to them alone. "Loads in" is arriving from another dimension, or
 * logging in already there. The dragon isn't always loaded the instant a
 * player arrives, so it's looked for over the first few seconds
 * ({@link #ARRIVAL_CHECKS} tries) rather than once. For a login the wait is
 * for the resource pack instead: the clip is in the pack, which is only sent
 * on join, so playing it at the join itself would reach a client that
 * doesn't know the sound yet.
 */
public final class DragonFightListener implements Listener {

    private static final long RESPAWN_INTERVAL_TICKS = 30L * 20L;
    private static final int PILLAR_COUNT = 10;
    private static final double PILLAR_CIRCLE_RADIUS = 42.0;
    private static final double ENDERMAN_RANGE = 64.0;
    private static final long ARRIVAL_CHECK_INTERVAL_TICKS = 10L;
    private static final int ARRIVAL_CHECKS = 10; // over 5 seconds

    private final Plugin plugin;
    // Each End world's pillar tops (the bedrock block), indexed by pillar.
    private final Map<World, Integer[]> pillarTops = new HashMap<>();
    // Players who logged in while in the End and haven't had their arrival
    // sound yet, waiting on the resource pack. Cleared on quit.
    private final Set<UUID> awaitingPackInEnd = new HashSet<>();
    private BukkitTask task;

    public DragonFightListener(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::respawnOneCrystal,
                RESPAWN_INTERVAL_TICKS, RESPAWN_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        arrive(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getWorld().getEnvironment() == World.Environment.THE_END) {
            awaitingPackInEnd.add(player.getUniqueId());
        }
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (event.getStatus() != PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) return;
        if (awaitingPackInEnd.remove(event.getPlayer().getUniqueId())) {
            arrive(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        awaitingPackInEnd.remove(event.getPlayer().getUniqueId());
    }

    private void arrive(Player player) {
        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.THE_END) return;
        playArrivalSoundIfDragonAlive(player, world, 1);
    }

    private void playArrivalSoundIfDragonAlive(Player player, World end, int attempt) {
        if (!player.isOnline() || player.getWorld() != end) return;
        if (dragonAlive(end)) {
            // To this player only, and from the player themselves so it's at
            // full volume wherever they stand. MASTER, like the other custom
            // sounds, so no single volume slider silences it alone.
            player.playSound(player, EndermanDodgeSoundPackListener.END_DRAGON_ALIVE_SOUND_KEY,
                    SoundCategory.MASTER, 1.0f, 1.0f);
            return;
        }
        if (attempt >= ARRIVAL_CHECKS) return;
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> playArrivalSoundIfDragonAlive(player, end, attempt + 1), ARRIVAL_CHECK_INTERVAL_TICKS);
    }

    private static boolean dragonAlive(World end) {
        DragonBattle battle = end.getEnderDragonBattle();
        if (battle == null) return false;
        EnderDragon dragon = battle.getEnderDragon();
        return dragon != null && !dragon.isDead();
    }

    private void respawnOneCrystal() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.THE_END) continue;
            if (!dragonAlive(world)) continue;

            List<Location> empty = new ArrayList<>();
            for (int i = 0; i < PILLAR_COUNT; i++) {
                Location spot = crystalSpot(world, i);
                if (spot != null && world.getNearbyEntitiesByType(EnderCrystal.class, spot, 1.0).isEmpty()) {
                    empty.add(spot);
                }
            }
            if (empty.isEmpty()) continue;

            Location spot = empty.get(ThreadLocalRandom.current().nextInt(empty.size()));
            // Shown with its bedrock base, as the fight's own crystals are.
            world.spawn(spot, EnderCrystal.class, crystal -> crystal.setShowingBottom(true));
        }
    }

    /**
     * Where pillar {@code index}'s crystal sits - centred on the block above
     * its bedrock - or null if the pillar's chunk isn't loaded or it has no
     * bedrock top (gone, or never generated).
     */
    private Location crystalSpot(World world, int index) {
        double angle = 2.0 * (-Math.PI + (Math.PI / 10.0) * index);
        int x = (int) Math.floor(PILLAR_CIRCLE_RADIUS * Math.cos(angle));
        int z = (int) Math.floor(PILLAR_CIRCLE_RADIUS * Math.sin(angle));
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;

        Integer[] tops = pillarTops.computeIfAbsent(world, w -> new Integer[PILLAR_COUNT]);
        Integer top = tops[index];
        if (top == null || !isPillarTop(world, x, top, z)) {
            top = findPillarTop(world, x, z);
            tops[index] = top;
        }
        return top == null ? null : new Location(world, x + 0.5, top + 1, z + 0.5);
    }

    private static Integer findPillarTop(World world, int x, int z) {
        for (int y = world.getMaxHeight() - 1; y > world.getMinHeight(); y--) {
            if (isPillarTop(world, x, y, z)) return y;
        }
        return null;
    }

    private static boolean isPillarTop(World world, int x, int y, int z) {
        return world.getType(x, y, z) == Material.BEDROCK && world.getType(x, y - 1, z) == Material.OBSIDIAN;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalDestroyed(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal) || crystal.isInvulnerable()) return;
        Player player = destroyer(event);
        if (player == null) return;

        List<Enderman> candidates = new ArrayList<>();
        for (Enderman enderman : player.getWorld().getNearbyEntitiesByType(Enderman.class, player.getLocation(), ENDERMAN_RANGE)) {
            // One already after this player wouldn't change anything.
            if (enderman.isValid() && enderman.getTarget() != player) candidates.add(enderman);
        }
        if (candidates.isEmpty()) return;
        candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())).setTarget(player);
    }

    // The player who struck the crystal directly or fired what hit it.
    private static Player destroyer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
