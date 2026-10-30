package com.example.vanillakfc.listeners;

import com.example.vanillakfc.items.CustomItems;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.SoundGroup;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Warden Heart drop and the Warden Helmet's effects.
 *
 * <ul>
 * <li><b>Heart:</b> every Warden drops one when it dies.
 * <li><b>Pulse</b> (every {@link #PULSE_TICKS}): each hostile mob within
 * {@link #RADIUS} blocks of a wearer glows for {@link #GLOW_TICKS}, for that
 * wearer only (see {@link WardenGlow}). With a 12-second glow on a 6-second
 * pulse, anything that stays in range glows without a break, and something
 * that leaves keeps glowing for up to 12 seconds after it was last caught.
 * The same pulse gives the wearer Resistance I for {@link #RESISTANCE_TICKS}
 * while they're in the Overworld below Y=0, so it never lapses down there.
 * <li><b>Footsteps:</b> each step a hostile mob within range takes is played
 * to the wearer a second time, on top of the vanilla sound everyone hears.
 * Vanilla's falls off to silence within 16 blocks; this one's volume is set
 * here, on a shallow slope from {@link #STEP_VOLUME_NEAR} beside the mob to
 * {@link #STEP_VOLUME_FAR} at 20 blocks, with creepers louder throughout.
 * It is played a couple of blocks from the wearer in the mob's direction,
 * so the client's own distance falloff stays almost constant and the slope
 * here is what's heard, while still coming from the right side.
 * </ul>
 */
public final class WardenHelmetListener implements Listener {

    private static final double RADIUS = 20.0;
    private static final double RADIUS_SQUARED = RADIUS * RADIUS;

    private static final long TASK_INTERVAL_TICKS = 2L;
    private static final long PULSE_TICKS = 6L * 20L;
    private static final long GLOW_TICKS = 12L * 20L;
    // The server re-sends a mob's true flags whenever one changes, and when
    // it comes into view, which drops the wearer's glow - re-sent this often.
    private static final long GLOW_REFRESH_TICKS = 20L;
    private static final int RESISTANCE_TICKS = 12 * 20;

    // Vanilla takes a step every 1/0.6 blocks walked (Entity#move).
    private static final double STEP_DISTANCE = 1.0 / 0.6;
    // More than this between two checks is a teleport, not walking.
    private static final double MAX_STEP_MOVE = 3.0;
    private static final double SOUND_OFFSET = 2.0;
    private static final float STEP_VOLUME_NEAR = 0.3F;
    private static final float STEP_VOLUME_FAR = 0.2F;
    private static final float CREEPER_STEP_VOLUME_NEAR = 0.6F;
    private static final float CREEPER_STEP_VOLUME_FAR = 0.45F;

    // Mobs with a step sound of their own; the rest step with the sound of
    // the block under them, as creepers do.
    private static final Map<EntityType, Sound> STEP_SOUNDS = new EnumMap<>(Map.ofEntries(
            Map.entry(EntityType.ZOMBIE, Sound.ENTITY_ZOMBIE_STEP),
            Map.entry(EntityType.HUSK, Sound.ENTITY_HUSK_STEP),
            Map.entry(EntityType.ZOMBIE_VILLAGER, Sound.ENTITY_ZOMBIE_VILLAGER_STEP),
            Map.entry(EntityType.DROWNED, Sound.ENTITY_DROWNED_STEP),
            Map.entry(EntityType.SKELETON, Sound.ENTITY_SKELETON_STEP),
            Map.entry(EntityType.STRAY, Sound.ENTITY_STRAY_STEP),
            Map.entry(EntityType.WITHER_SKELETON, Sound.ENTITY_WITHER_SKELETON_STEP),
            Map.entry(EntityType.BOGGED, Sound.ENTITY_BOGGED_STEP),
            Map.entry(EntityType.SPIDER, Sound.ENTITY_SPIDER_STEP),
            Map.entry(EntityType.CAVE_SPIDER, Sound.ENTITY_SPIDER_STEP),
            Map.entry(EntityType.SILVERFISH, Sound.ENTITY_SILVERFISH_STEP),
            Map.entry(EntityType.ENDERMITE, Sound.ENTITY_ENDERMITE_STEP),
            Map.entry(EntityType.RAVAGER, Sound.ENTITY_RAVAGER_STEP),
            Map.entry(EntityType.WARDEN, Sound.ENTITY_WARDEN_STEP),
            Map.entry(EntityType.HOGLIN, Sound.ENTITY_HOGLIN_STEP),
            Map.entry(EntityType.ZOGLIN, Sound.ENTITY_ZOGLIN_STEP),
            Map.entry(EntityType.PIGLIN_BRUTE, Sound.ENTITY_PIGLIN_BRUTE_STEP)
    ));

    private static final class StepState {
        Location last;
        double walked;

        StepState(Location last) {
            this.last = last;
        }
    }

    private final Plugin plugin;
    private BukkitTask task;
    private long ticks;
    // Per wearer: each mob glowing for them, and the tick its glow ends.
    private final Map<UUID, Map<UUID, Long>> glowing = new HashMap<>();
    private final Map<UUID, StepState> steps = new HashMap<>();

    public WardenHelmetListener(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TASK_INTERVAL_TICKS, TASK_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Leave nobody with a glow that would otherwise never be taken back.
        for (Map.Entry<UUID, Map<UUID, Long>> entry : glowing.entrySet()) {
            Player wearer = Bukkit.getPlayer(entry.getKey());
            if (wearer == null) continue;
            for (UUID mobId : entry.getValue().keySet()) {
                Entity mob = Bukkit.getEntity(mobId);
                if (mob != null && mob.isValid()) WardenGlow.hide(wearer, mob);
            }
        }
        glowing.clear();
        steps.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWardenDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Warden) {
            event.getDrops().add(CustomItems.wardenHeart());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        glowing.remove(event.getPlayer().getUniqueId());
    }

    private void tick() {
        ticks += TASK_INTERVAL_TICKS;
        List<Player> wearers = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() != GameMode.SPECTATOR && CustomItems.isWardenHelmet(player.getInventory().getHelmet())) {
                wearers.add(player);
            }
        }

        if (ticks % PULSE_TICKS == 0) {
            for (Player wearer : wearers) pulse(wearer);
        }
        if (ticks % GLOW_REFRESH_TICKS == 0) {
            refreshGlows();
        }
        footsteps(wearers);
    }

    private void pulse(Player wearer) {
        Map<UUID, Long> mine = glowing.computeIfAbsent(wearer.getUniqueId(), id -> new HashMap<>());
        long endsAt = ticks + GLOW_TICKS;
        for (LivingEntity mob : hostilesNear(wearer.getLocation())) {
            mine.put(mob.getUniqueId(), endsAt);
            WardenGlow.show(wearer, mob);
        }

        Location at = wearer.getLocation();
        if (at.getWorld().getEnvironment() == World.Environment.NORMAL && at.getY() < 0) {
            wearer.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, RESISTANCE_TICKS, 0, true, true, true));
        }
    }

    // Re-sends every live glow, and takes back the ones that have run out.
    // Whoever took the helmet off keeps what they had until it runs out, as
    // with a potion effect.
    private void refreshGlows() {
        for (Iterator<Map.Entry<UUID, Map<UUID, Long>>> wearers = glowing.entrySet().iterator(); wearers.hasNext(); ) {
            Map.Entry<UUID, Map<UUID, Long>> entry = wearers.next();
            Player wearer = Bukkit.getPlayer(entry.getKey());
            if (wearer == null) {
                wearers.remove();
                continue;
            }
            for (Iterator<Map.Entry<UUID, Long>> mobs = entry.getValue().entrySet().iterator(); mobs.hasNext(); ) {
                Map.Entry<UUID, Long> glow = mobs.next();
                Entity mob = Bukkit.getEntity(glow.getKey());
                if (mob == null || !mob.isValid()) {
                    mobs.remove();
                } else if (ticks >= glow.getValue()) {
                    WardenGlow.hide(wearer, mob);
                    mobs.remove();
                } else {
                    WardenGlow.show(wearer, mob);
                }
            }
            if (entry.getValue().isEmpty()) wearers.remove();
        }
    }

    private void footsteps(List<Player> wearers) {
        // Each mob walks once however many wearers hear it, so the steps are
        // counted per mob, then played to every wearer close enough.
        Map<LivingEntity, List<Player>> listeners = new HashMap<>();
        for (Player wearer : wearers) {
            for (LivingEntity mob : hostilesNear(wearer.getLocation())) {
                listeners.computeIfAbsent(mob, m -> new ArrayList<>()).add(wearer);
            }
        }

        Set<UUID> seen = new HashSet<>();
        for (Map.Entry<LivingEntity, List<Player>> entry : listeners.entrySet()) {
            LivingEntity mob = entry.getKey();
            seen.add(mob.getUniqueId());
            if (tookStep(mob)) {
                playStep(mob, entry.getValue());
            }
        }
        steps.keySet().retainAll(seen);
    }

    private boolean tookStep(LivingEntity mob) {
        Location now = mob.getLocation();
        StepState state = steps.get(mob.getUniqueId());
        if (state == null || state.last.getWorld() != now.getWorld()) {
            steps.put(mob.getUniqueId(), new StepState(now));
            return false;
        }
        double dx = now.getX() - state.last.getX();
        double dz = now.getZ() - state.last.getZ();
        double moved = Math.sqrt(dx * dx + dz * dz);
        state.last = now;
        if (moved > MAX_STEP_MOVE || !mob.isOnGround() || mob.isSilent()) return false;

        state.walked += moved;
        if (state.walked < STEP_DISTANCE) return false;
        state.walked %= STEP_DISTANCE;
        return true;
    }

    private static void playStep(LivingEntity mob, List<Player> wearers) {
        Location mobAt = mob.getLocation();
        Sound sound = STEP_SOUNDS.get(mob.getType());
        float pitch = 1.0F;
        if (sound == null) {
            Block under = mobAt.clone().subtract(0, 0.2, 0).getBlock();
            if (under.isEmpty()) under = mobAt.getBlock();
            SoundGroup group = under.getBlockData().getSoundGroup();
            sound = group.getStepSound();
            pitch = group.getPitch();
        }
        boolean creeper = mob instanceof Creeper;
        float near = creeper ? CREEPER_STEP_VOLUME_NEAR : STEP_VOLUME_NEAR;
        float far = creeper ? CREEPER_STEP_VOLUME_FAR : STEP_VOLUME_FAR;

        for (Player wearer : wearers) {
            Location ear = wearer.getEyeLocation();
            Vector toMob = mobAt.toVector().subtract(ear.toVector());
            double distance = toMob.length();
            if (distance > RADIUS) continue;
            Location at = distance < 0.01 ? ear : ear.clone().add(toMob.multiply(Math.min(distance, SOUND_OFFSET) / distance));
            float volume = (float) (near + (far - near) * (distance / RADIUS));
            wearer.playSound(at, sound, SoundCategory.HOSTILE, volume, pitch);
        }
    }

    private static List<LivingEntity> hostilesNear(Location center) {
        List<LivingEntity> found = new ArrayList<>();
        for (LivingEntity entity : center.getWorld().getNearbyLivingEntities(center, RADIUS)) {
            if (entity instanceof Enemy && !entity.isDead()
                    && entity.getLocation().distanceSquared(center) <= RADIUS_SQUARED) {
                found.add(entity);
            }
        }
        return found;
    }
}
