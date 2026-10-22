package com.example.vanillakfc.listeners;

import com.destroystokyo.paper.event.block.BeaconEffectEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Beacons reach as far as the pyramid under them is worth, and the whole
 * height of the world at that reach.
 *
 * <ul>
 * <li><b>Height:</b> an affected column runs from the bottom of the world to
 * the top. Vanilla's reaches all the way up but only {@code range} blocks
 * below the beacon.
 * <li><b>Radius:</b> a flat {@link #BASE_RADIUS} plus the sum of the blocks
 * in the layers that count toward the beacon's tier - Iron 0.3, Gold 0.5,
 * Emerald 0.7, Diamond 2.0 and Netherite 10.0 each - rounded up to a whole
 * block, in place of vanilla's 10 + 10 per tier. Blocks in an incomplete
 * layer below the last full one don't count, as they don't for the tier
 * either. A full iron tier 1 is 10 + 2.7 = 12.7, so 13. Like vanilla's, the
 * reach is a square: X and Z are each checked against the radius on their
 * own, so the corners count, measured out from the beacon block's own edges.
 * <li><b>Netherite tier 4</b> (all 164 blocks netherite): every player in the
 * beacon's dimension, wherever they are.
 * </ul>
 *
 * <p>Vanilla only applies a beacon's effects through its own fixed box, and
 * nothing in the API widens it downward, so this plugin applies them itself
 * and cancels vanilla's own application ({@link BeaconEffectEvent}) to avoid
 * doubling up. Everything else is vanilla's: the same 4-second cycle, effect
 * durations, amplifiers, secondary effect rules, and the requirement that
 * the beam reaches the sky.
 *
 * <p><b>Keeping beacons running.</b> A beacon with a reach far beyond its own
 * chunk would stop the moment that chunk unloaded, so every active beacon's
 * chunk is kept loaded with a plugin chunk ticket - which also keeps that
 * chunk's mobs, crops and redstone running, as a spawn chunk would. A beacon
 * that loses its pyramid or its beam lets its chunk go again, and picks it
 * back up once it's working. Beacons are remembered per world (in the
 * world's own saved data) so their chunks are loaded again after a restart
 * without anyone having to visit; beacons from before this existed are found
 * as their chunks load.
 */
public final class BeaconListener implements Listener {

    private static final long CYCLE_TICKS = 80L;
    private static final int MAX_TIER = 4;
    private static final int BASE_RADIUS = 10;
    // Half the player hitbox width - vanilla tests the player's box against
    // its own, so reach is measured to the player's edge, not their centre.
    private static final double PLAYER_HALF_WIDTH = 0.3;

    // In tenths of a block, so the sum is exact before it's rounded up -
    // added as doubles, ten iron blocks come to 3.0000000000000004 and round
    // up a whole block too far.
    private static final Map<Material, Integer> TENTHS_PER_BLOCK = new EnumMap<>(Map.of(
            Material.IRON_BLOCK, 3,
            Material.GOLD_BLOCK, 5,
            Material.EMERALD_BLOCK, 7,
            Material.DIAMOND_BLOCK, 20,
            Material.NETHERITE_BLOCK, 100
    ));

    private final Plugin plugin;
    private final NamespacedKey beaconsKey;
    // Packed positions (see pack()) of every beacon known in each world.
    private final Map<World, Set<Long>> beacons = new HashMap<>();
    // Chunk keys (Chunk#getChunkKey) this plugin currently holds a ticket on.
    private final Map<World, Set<Long>> ticketed = new HashMap<>();
    private BukkitTask task;

    public BeaconListener(Plugin plugin) {
        this.plugin = plugin;
        this.beaconsKey = new NamespacedKey(plugin, "beacons");
    }

    public void start() {
        for (World world : Bukkit.getWorlds()) {
            long[] stored = world.getPersistentDataContainer().get(beaconsKey, PersistentDataType.LONG_ARRAY);
            if (stored != null) {
                Set<Long> known = beaconsIn(world);
                for (long key : stored) known.add(key);
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                findBeacons(chunk);
            }
            // Loaded straight away, so a beacon far from every player is
            // running again from the first cycle after a restart. Any that
            // turn out to be inactive let their chunk go on that cycle.
            for (long key : beaconsIn(world)) {
                hold(world, chunkKeyOf(key));
            }
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::cycle, CYCLE_TICKS, CYCLE_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (World world : Bukkit.getWorlds()) {
            save(world);
        }
    }

    // Every beacon's effects come from cycle() instead.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onVanillaBeaconEffect(BeaconEffectEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (block.getType() == Material.BEACON) {
            track(block);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        findBeacons(event.getChunk());
    }

    private void findBeacons(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities(block -> block.getType() == Material.BEACON, false)) {
            track(state.getBlock());
        }
    }

    private void track(Block block) {
        if (beaconsIn(block.getWorld()).add(pack(block.getX(), block.getY(), block.getZ()))) {
            save(block.getWorld());
        }
    }

    private void cycle() {
        for (World world : Bukkit.getWorlds()) {
            Set<Long> known = beaconsIn(world);
            if (known.isEmpty() && ticketedIn(world).isEmpty()) continue;

            Set<Long> wantLoaded = new HashSet<>();
            boolean forgotAny = false;
            for (Long key : new ArrayList<>(known)) {
                int x = unpackX(key), y = unpackY(key), z = unpackZ(key);
                // Only judged while loaded. An inactive beacon's chunk is left to
                // unload, and is looked at again whenever something loads it.
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                // The base reaches 4 blocks out, which can cross into a
                // neighbouring chunk. Reading an unloaded one would load it
                // (or generate it) on the spot, so the beacon waits until its
                // whole base is loaded - keeping the chunk it already holds.
                if (!baseLoaded(world, x, z)) {
                    if (ticketedIn(world).contains(chunkKeyOf(key))) wantLoaded.add(chunkKeyOf(key));
                    continue;
                }

                Block block = world.getBlockAt(x, y, z);
                if (block.getType() != Material.BEACON || !(block.getState(false) instanceof Beacon beacon)) {
                    known.remove(key);
                    forgotAny = true;
                    continue;
                }
                if (applyEffects(block, beacon)) {
                    wantLoaded.add(chunkKeyOf(key));
                }
            }
            if (forgotAny) save(world);

            for (Long chunkKey : new ArrayList<>(ticketedIn(world))) {
                if (!wantLoaded.contains(chunkKey)) release(world, chunkKey);
            }
            for (Long chunkKey : wantLoaded) {
                hold(world, chunkKey);
            }
        }
    }

    /**
     * Applies one beacon's effects, by vanilla's rules apart from where they
     * reach.
     *
     * @return whether the beacon is active - a pyramid under it and a clear
     *         beam above - and so should be kept loaded
     */
    private boolean applyEffects(Block block, Beacon beacon) {
        Pyramid pyramid = scanPyramid(block);
        if (pyramid.tier == 0 || !beamReachesSky(block)) return false;

        PotionEffect primaryEffect = beacon.getPrimaryEffect();
        if (primaryEffect == null) return true;
        PotionEffectType primary = primaryEffect.getType();
        PotionEffect secondaryEffect = beacon.getSecondaryEffect();
        PotionEffectType secondary = secondaryEffect == null ? null : secondaryEffect.getType();

        int tier = pyramid.tier;
        int duration = (9 + tier * 2) * 20;
        // Picking the primary effect again as the secondary (tier 4) upgrades
        // it to level II. That choice can't be read from the secondary: Paper's
        // getSecondaryEffect() is null whenever it matches the primary, since
        // vanilla never applies it as an effect of its own. The primary carries
        // vanilla's own amplifier for it instead - 1 in exactly that case.
        boolean upgradedPrimary = primaryEffect.getAmplifier() >= 1;
        int amplifier = tier >= MAX_TIER && upgradedPrimary ? 1 : 0;
        boolean hasSecondary = tier >= MAX_TIER && secondary != null && !Objects.equals(primary, secondary);

        List<PotionEffect> effects = new ArrayList<>(2);
        effects.add(new PotionEffect(primary, duration, amplifier, true, true));
        if (hasSecondary) {
            effects.add(new PotionEffect(secondary, duration, 0, true, true));
        }

        double minX = block.getX() - pyramid.radius - PLAYER_HALF_WIDTH;
        double maxX = block.getX() + 1 + pyramid.radius + PLAYER_HALF_WIDTH;
        double minZ = block.getZ() - pyramid.radius - PLAYER_HALF_WIDTH;
        double maxZ = block.getZ() + 1 + pyramid.radius + PLAYER_HALF_WIDTH;
        for (Player player : block.getWorld().getPlayers()) {
            if (player.isDead()) continue;
            if (!pyramid.wholeDimension) {
                double px = player.getX(), pz = player.getZ();
                if (px < minX || px > maxX || pz < minZ || pz > maxZ) continue;
            }
            for (PotionEffect effect : effects) {
                player.addPotionEffect(effect);
            }
        }
        return true;
    }

    private static boolean baseLoaded(World world, int x, int z) {
        for (int dx = -MAX_TIER; dx <= MAX_TIER; dx += 2 * MAX_TIER) {
            for (int dz = -MAX_TIER; dz <= MAX_TIER; dz += 2 * MAX_TIER) {
                if (!world.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) return false;
            }
        }
        return true;
    }

    private record Pyramid(int tier, int radius, boolean wholeDimension) {}

    // Vanilla's own tier count: layer n sits n blocks below the beacon and
    // spans n blocks either side; the tier is how many layers are complete in
    // a row. Only those layers' blocks count toward the radius.
    private static Pyramid scanPyramid(Block beacon) {
        World world = beacon.getWorld();
        int tier = 0;
        int tenths = 0;
        boolean allNetherite = true;
        for (int layer = 1; layer <= MAX_TIER; layer++) {
            int y = beacon.getY() - layer;
            if (y < world.getMinHeight()) break;

            int layerTenths = 0;
            boolean layerNetherite = true;
            boolean complete = true;
            for (int x = beacon.getX() - layer; x <= beacon.getX() + layer && complete; x++) {
                for (int z = beacon.getZ() - layer; z <= beacon.getZ() + layer; z++) {
                    Material type = world.getType(x, y, z);
                    if (!Tag.BEACON_BASE_BLOCKS.isTagged(type)) {
                        complete = false;
                        break;
                    }
                    // A base block this list doesn't price (a datapack can add
                    // more to the tag) counts for the tier but adds no reach.
                    layerTenths += TENTHS_PER_BLOCK.getOrDefault(type, 0);
                    layerNetherite &= type == Material.NETHERITE_BLOCK;
                }
            }
            if (!complete) break;
            tier = layer;
            tenths += layerTenths;
            allNetherite &= layerNetherite;
        }
        // Rounded up: 12.7 reaches the 13th block out.
        int radius = BASE_RADIUS + (tenths + 9) / 10;
        return new Pyramid(tier, radius, tier == MAX_TIER && allNetherite);
    }

    // Vanilla's beam stops at any block that blocks all light, bedrock
    // excepted. Material#isOccluding covers full opaque blocks; tinted glass
    // is the one block that blocks light without occluding, so it's named.
    private static boolean beamReachesSky(Block beacon) {
        World world = beacon.getWorld();
        for (int y = beacon.getY() + 1; y < world.getMaxHeight(); y++) {
            Material type = world.getType(beacon.getX(), y, beacon.getZ());
            if (type == Material.BEDROCK) continue;
            if (type.isOccluding() || type == Material.TINTED_GLASS) return false;
        }
        return true;
    }

    private void hold(World world, long chunkKey) {
        if (ticketedIn(world).add(chunkKey)) {
            world.addPluginChunkTicket(chunkX(chunkKey), chunkZ(chunkKey), plugin);
        }
    }

    private void release(World world, long chunkKey) {
        if (ticketedIn(world).remove(chunkKey)) {
            world.removePluginChunkTicket(chunkX(chunkKey), chunkZ(chunkKey), plugin);
        }
    }

    private void save(World world) {
        Set<Long> known = beaconsIn(world);
        long[] keys = new long[known.size()];
        int i = 0;
        for (long key : known) keys[i++] = key;
        world.getPersistentDataContainer().set(beaconsKey, PersistentDataType.LONG_ARRAY, keys);
    }

    private Set<Long> beaconsIn(World world) {
        return beacons.computeIfAbsent(world, w -> new LinkedHashSet<>());
    }

    private Set<Long> ticketedIn(World world) {
        return ticketed.computeIfAbsent(world, w -> new HashSet<>());
    }

    // Vanilla's own BlockPos packing - 26 bits each for x and z, 12 for y -
    // which covers every position a world can have. (Paper's Block#getBlockKey
    // is deprecated for only fitting part of today's height range.)
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    private static int unpackY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    private static int unpackZ(long packed) {
        return (int) (packed << 26 >> 38);
    }

    // Same packing as Chunk#getChunkKey.
    private static long chunkKeyOf(long packed) {
        return Chunk.getChunkKey(unpackX(packed) >> 4, unpackZ(packed) >> 4);
    }

    private static int chunkX(long chunkKey) {
        return (int) chunkKey;
    }

    private static int chunkZ(long chunkKey) {
        return (int) (chunkKey >> 32);
    }
}
