package com.example.vanillakfc.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gates and slows natural growth of farmland crops:
 *
 * <ul>
 * <li>Growth runs at {@link #NIGHT_AND_STORM_KEEP_CHANCE} of its usual rate at
 * night and during thunderstorms - slowed, not stopped.
 * <li>The crop needs a direct, unobstructed view of the sky in the column
 * above it - checked via sky light rather than a hand-rolled block scan, so
 * glass, open trapdoors, iron bars, etc. still count (they don't attenuate
 * sky light) while a solid roof anywhere in the column does not.
 * <li>Growth may be thinned by {@link #GROWTH_KEEP_CHANCE}, though that is
 * currently 1.0 (no thinning) - see below for why it earns so little.
 * <li>A crop is held in step with its neighbours: one that has run more than
 * {@link #MAX_STAGES_AHEAD} stages ahead of the least-grown crop around it is
 * blocked until the field catches up, and one sitting at that trailing edge
 * grows unthinned so it recovers at full speed.
 * </ul>
 *
 * <p>That last rule exists because vanilla growth is a per-block lottery:
 * every crop independently waits on random ticks, so a field planted in one
 * go matures raggedly - the luckiest blocks finish many times sooner than the
 * unluckiest. Thinning the rolls alone makes that worse, since it compounds
 * the same randomness. Capping how far any one crop may run ahead is what
 * actually pulls a field together, because it constrains the fast blocks as
 * well as helping the slow ones; permitting growth more often can only ever
 * do the latter.
 *
 * <p>Monte-Carlo over a solid 9x9 wheat plot on hydrated farmland, calibrated
 * to vanilla's random-tick rate (3/4096 per block per tick) and its 1/6 growth
 * roll, with the chunk held loaded throughout and night taken from
 * {@link WorldClock} (ticks 12523-23478, about 46% of each cycle) - minutes to
 * reach each mark:
 *
 * <pre>
 *                                    50%     90%    100%   first
 *   vanilla                         45.6    71.2   103.1    15.4
 *   thinning 2/3, night blocked    121.5   191.7   279.8    37.9
 *   cap, no thinning, night blocked 202.2  224.4   261.4   191.9
 *   cap, no thinning, night halved 144.3   159.8   185.9   137.1  &lt;- current
 *   cap, no thinning, night ignored 112.5  124.6   144.7   106.9
 * </pre>
 *
 * <p>Two things that table is worth remembering for:
 *
 * <p>The thinning earns almost nothing once the cap exists - dropping it moved
 * the full-field time by about 10%, where without the cap the same change is
 * worth 34%. A crop at the trailing edge bypasses the thinning roll, and under
 * the cap most crops sit at that edge most of the time, so the two mechanisms
 * were largely fighting over the same blocks. That is why
 * {@link #GROWTH_KEEP_CHANCE} now sits at 1.0.
 *
 * <p>The night rule, by contrast, is a real lever, because it applies to every
 * crop including the trailing ones. Softening it from a block to a halving cut
 * the full-field time by 29% (261 to 186 minutes). The remaining knobs are
 * that multiplier and {@link #MAX_STAGES_AHEAD} - widening the latter loosens
 * the formation and shortens the wait, narrowing it tightens the field at the
 * cost of a slower harvest.
 *
 * <p>One consequence worth knowing: a single crop replanted into an otherwise
 * mature field counts as the trailing edge and will briefly hold the rest at
 * {@code MAX_STAGES_AHEAD} until it catches up. It resolves on its own,
 * because a trailing crop grows at full speed.
 *
 * <p>Applies to everything in vanilla's own {@code #minecraft:crops} tag -
 * wheat, carrots, potatoes, beetroot, pumpkin and melon stems, torchflower
 * and pitcher crops - rather than a hand-kept list, so the set can't drift
 * out of date the way the old one did (it had missed the last two). Nether
 * wart isn't in that tag and stays excluded, since it has no sky above it in
 * the Nether; plants that don't grow on farmland (sugar cane, cactus, bamboo,
 * berries, cocoa, kelp) are untouched too. A stem's pumpkin or melon still
 * appears at vanilla's rate once the stem has matured, because that growth is
 * reported against the empty block the fruit appears in, not the stem.
 *
 * <p>Bone meal is exempt from all of this and behaves exactly as in vanilla -
 * see {@link #bonemealTargets} for how it's told apart from natural growth.
 */
public final class CropGrowthListener implements Listener {

    private static final byte MAX_SKY_LIGHT = 15;
    /**
     * Fraction of vanilla's successful growth rolls that are kept. 1.0 leaves
     * vanilla's rate alone; the neighbour cap below is what paces the field
     * now. Lower it to slow crops that are running ahead of the trailing edge
     * (it does not apply to the trailing crops themselves).
     */
    private static final double GROWTH_KEEP_CHANCE = 1.0;

    /**
     * Growth multiplier at night and during thunderstorms. Applies to every
     * crop, trailing ones included - it describes the growing conditions, not
     * the field's internal pacing, so it stacks on top of everything below.
     */
    private static final double NIGHT_AND_STORM_KEEP_CHANCE = 0.5;

    /** How far a crop may outpace the least-grown crop near it before it stalls. */
    private static final int MAX_STAGES_AHEAD = 2;
    /** Half-width of the square of blocks treated as "the same field". */
    private static final int FIELD_RADIUS = 4;
    /** Returned when a crop has no same-type company to keep step with. */
    private static final int NO_NEIGHBOURS = Integer.MIN_VALUE;

    /**
     * Blocks bone meal is being applied to during {@link #bonemealTick}.
     *
     * <p>Bone meal on a crop fires the very same {@link BlockGrowEvent} as a
     * natural random-tick growth, and the event carries nothing saying which
     * it was - so without this, every rule below was quietly applied to bone
     * meal too, and the neighbour cap refused it outright once a crop was
     * {@link #MAX_STAGES_AHEAD} stages ahead of its field. Paper's
     * {@code BlockFertilizeEvent} doesn't help: it fires after the growth has
     * already been captured, too late to exempt it.
     *
     * <p>What does work is that both ways of applying bone meal announce
     * themselves first - a player's right-click and a dispenser firing - and
     * the growth then happens synchronously within that same server tick. The
     * target is noted there and recognised here; the set only ever holds the
     * current tick's entries, so it can't grow or go stale.
     */
    private final Set<BlockKey> bonemealTargets = new HashSet<>();
    private int bonemealTick = -1;

    private record BlockKey(UUID world, int x, int y, int z) {
        static BlockKey of(Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    @EventHandler
    public void onPlayerBonemeal(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        ItemStack item = event.getItem();
        if (item != null && item.getType() == Material.BONE_MEAL) {
            markBonemealTarget(event.getClickedBlock());
        }
    }

    @EventHandler
    public void onDispenserBonemeal(BlockDispenseEvent event) {
        if (event.getItem().getType() != Material.BONE_MEAL
                || !(event.getBlock().getBlockData() instanceof Directional facing)) {
            return;
        }
        markBonemealTarget(event.getBlock().getRelative(facing.getFacing()));
    }

    // The halves above and below are marked too: a pitcher plant is two
    // blocks tall, and the growth may be reported against the other half
    // from the one that was clicked. Marking a neighbour that isn't a crop
    // costs nothing, since only a crop's growth event ever looks it up.
    private void markBonemealTarget(Block target) {
        int now = Bukkit.getCurrentTick();
        if (now != bonemealTick) {
            bonemealTargets.clear();
            bonemealTick = now;
        }
        bonemealTargets.add(BlockKey.of(target));
        bonemealTargets.add(BlockKey.of(target.getRelative(BlockFace.UP)));
        bonemealTargets.add(BlockKey.of(target.getRelative(BlockFace.DOWN)));
    }

    private boolean isBonemealTarget(Block block) {
        return Bukkit.getCurrentTick() == bonemealTick && bonemealTargets.contains(BlockKey.of(block));
    }

    @EventHandler(ignoreCancelled = true)
    public void onCropGrow(BlockGrowEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        if (!Tag.CROPS.isTagged(type)) {
            return;
        }

        // Bone meal behaves exactly as in vanilla: none of the rules below -
        // sky, night, weather, or keeping step with the field - apply to it.
        if (isBonemealTarget(block)) {
            return;
        }

        // Still absolute rather than a penalty: a crop roofed over has no sky
        // to grow towards at any hour, so there is nothing to scale.
        if (block.getLightFromSky() < MAX_SKY_LIGHT) {
            event.setCancelled(true);
            return;
        }

        World world = block.getWorld();
        double keepChance = 1.0;

        // Night by the clock, not isDayTime(): the latter measures sky
        // brightness, so plain rain darkening a dusk sky would have counted
        // as night too. Thunderstorms are penalised explicitly instead.
        if (WorldClock.isNight(world) || world.isThundering()) {
            keepChance *= NIGHT_AND_STORM_KEEP_CHANCE;
        }

        if (block.getBlockData() instanceof Ageable ageable) {
            int age = ageable.getAge();
            int trailingAge = trailingNeighbourAge(block, type);

            if (trailingAge == NO_NEIGHBOURS) {
                // A lone crop has no field to stay level with, so it just takes
                // the plain rate rather than the trailing-edge exemption.
                keepChance *= GROWTH_KEEP_CHANCE;
            } else if (age - trailingAge >= MAX_STAGES_AHEAD) {
                event.setCancelled(true);
                return;
            } else if (age > trailingAge) {
                // Ahead of the back of the field, so it pays the pacing
                // thinning; the trailing crops themselves are exempt, which is
                // what makes falling behind self-correcting.
                keepChance *= GROWTH_KEEP_CHANCE;
            }
        } else {
            keepChance *= GROWTH_KEEP_CHANCE;
        }

        if (keepChance < 1.0 && ThreadLocalRandom.current().nextDouble() >= keepChance) {
            event.setCancelled(true);
        }
    }

    // Lowest age among same-type crops on this block's own level nearby.
    // Deliberately skips unloaded chunks rather than pulling them in: this
    // runs on every growth roll, and touching a neighbouring chunk purely to
    // read a crop age would be a needless load.
    private static int trailingNeighbourAge(Block block, Material type) {
        World world = block.getWorld();
        int y = block.getY();
        int trailing = Integer.MAX_VALUE;
        boolean found = false;

        for (int dx = -FIELD_RADIUS; dx <= FIELD_RADIUS; dx++) {
            for (int dz = -FIELD_RADIUS; dz <= FIELD_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int x = block.getX() + dx;
                int z = block.getZ() + dz;
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                Block other = world.getBlockAt(x, y, z);
                // Cheap type check first - only same-type crops are worth
                // materialising BlockData for.
                if (other.getType() != type) {
                    continue;
                }
                if (other.getBlockData() instanceof Ageable otherAgeable) {
                    trailing = Math.min(trailing, otherAgeable.getAge());
                    found = true;
                }
            }
        }
        return found ? trailing : NO_NEIGHBOURS;
    }
}
