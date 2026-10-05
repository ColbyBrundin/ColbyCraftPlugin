package com.example.vanillakfc.listeners;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Blaze;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vex;
import org.bukkit.entity.Warden;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Makes tamed wolves ("dogs") actually draw a fight away from their owner,
 * and look for one when they have nothing to do.
 *
 * <ul>
 *   <li>retaliation: a hostile mob a dog bites turns on the dog, rather than
 *       carrying on after the player while the dog chews on it. Vanilla
 *       only does this reliably for some mobs; here it's every hostile the
 *       dog can fight, unless it's already busy fighting a wolf. The mob goes
 *       back to whoever it was after once the dog is dead or out of range,
 *       through its own targeting.</li>
 *   <li>guarding: a dog that is standing (not told to sit), has an owner
 *       online nearby and nothing to attack, goes after the nearest hostile
 *       within {@link #GUARD_RADIUS} blocks of its owner that it can see.</li>
 * </ul>
 *
 * <p>Both leave out what vanilla wolves won't fight, or can't: creepers and
 * ghasts (vanilla's own exclusions - a creeper turning on a dog would also
 * sidestep {@link CreeperBehaviorTask}'s rules on when one may light its
 * fuse), anything that flies out of reach, wardens, and the neutral mobs
 * (endermen, piglins, zombified piglins) that only fight if provoked - a dog
 * picking a fight with one would start a fight the player didn't want.
 */
public final class WolfGuardListener implements Listener {

    /** How far from the owner a dog will look for something to attack. */
    private static final double GUARD_RADIUS = 16.0;
    /** Past this the dog is too far from its owner to be guarding them. */
    private static final double MAX_OWNER_DISTANCE = 24.0;
    private static final long SCAN_INTERVAL_TICKS = 10L;

    private final Plugin plugin;
    private BukkitTask task;

    public WolfGuardListener(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::guard, SCAN_INTERVAL_TICKS, SCAN_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // MONITOR + ignoreCancelled: only a bite that actually landed counts.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDogBite(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Wolf wolf) || !wolf.isTamed()) return;
        if (!(event.getEntity() instanceof Mob mob) || !isFightable(mob)) return;
        // Already fighting a dog (this one or another) - leave it be, rather
        // than have it flip between dogs every time a different one bites.
        if (mob.getTarget() instanceof Wolf) return;
        mob.setTarget(wolf);
    }

    private void guard() {
        for (World world : Bukkit.getWorlds()) {
            for (Wolf wolf : world.getEntitiesByClass(Wolf.class)) {
                if (!wolf.isTamed() || wolf.isSitting() || hasLiveTarget(wolf)) continue;
                Player owner = onlineOwner(wolf);
                if (owner == null || owner.getWorld() != world) continue;
                if (owner.getLocation().distanceSquared(wolf.getLocation()) > MAX_OWNER_DISTANCE * MAX_OWNER_DISTANCE) continue;

                LivingEntity threat = nearestThreat(wolf, owner);
                if (threat != null) {
                    wolf.setTarget(threat);
                }
            }
        }
    }

    // Nearest to the owner, since protecting them is the point; line of sight
    // from the dog, so it doesn't go tunnelling off after something behind a
    // wall or down a cave.
    private static LivingEntity nearestThreat(Wolf wolf, Player owner) {
        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity entity : owner.getNearbyEntities(GUARD_RADIUS, GUARD_RADIUS, GUARD_RADIUS)) {
            if (!(entity instanceof Mob mob) || !isFightable(mob) || mob.isDead()) continue;
            double distance = entity.getLocation().distanceSquared(owner.getLocation());
            if (distance > GUARD_RADIUS * GUARD_RADIUS || distance >= nearestDistance) continue;
            if (!wolf.hasLineOfSight(mob)) continue;
            nearest = mob;
            nearestDistance = distance;
        }
        return nearest;
    }

    private static boolean isFightable(Mob mob) {
        if (!(mob instanceof Enemy)) return false;
        return !(mob instanceof Creeper || mob instanceof Ghast
                || mob instanceof Phantom || mob instanceof Vex || mob instanceof Blaze
                || mob instanceof Warden
                || mob instanceof Enderman || mob instanceof Piglin || mob instanceof PigZombie);
    }

    private static boolean hasLiveTarget(Wolf wolf) {
        LivingEntity target = wolf.getTarget();
        return target != null && target.isValid() && !target.isDead();
    }

    private static Player onlineOwner(Wolf wolf) {
        AnimalTamer owner = wolf.getOwner();
        if (!(owner instanceof Player player) || !player.isOnline() || player.isDead()) return null;
        // Nothing to guard a player who can't be attacked anyway.
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return null;
        return player;
    }
}
