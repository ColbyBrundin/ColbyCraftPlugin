package com.example.vanillakfc.listeners;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * On death, gear (weapons/tools/armor) and arrows stay with the player instead of
 * dropping, and XP level only drops by a flat amount (floored at 0) instead
 * of resetting to 0 or dropping as orbs; everything else drops as usual but
 * takes twice as long to despawn.
 *
 * <p>Gear kept on death waits on the player's own persistent data until they
 * respawn, so quitting on the death screen - or a server restart while they
 * are still on it - doesn't lose it.
 *
 * <p>The doubled lifetime works by tagging each death drop and cancelling its
 * first despawn. Cancelling resets the item's age to 0 (vanilla does that
 * itself), so it simply lives a second full despawn cycle at whatever rate the
 * world is configured for, and like any item only ages while its chunk is
 * loaded.
 *
 * <p>Tagging is the fragile part. An earlier version tagged items from
 * {@code EntityDropItemEvent}, but Paper never fires that for death drops -
 * only for {@code spawnAtLocation}, which death drops don't go through - so
 * nothing was ever tagged and every death drop vanished at vanilla's 5
 * minutes. Death drops are spawned straight after {@link PlayerDeathEvent}
 * finishes, within the same tick, so the final drop list is noted there and
 * each spawning item is matched against it in {@link ItemSpawnEvent}.
 */
public final class DeathDropListener implements Listener {

    private static final int LEVELS_LOST_ON_DEATH = 3;

    private final Plugin plugin;
    private final NamespacedKey deathDropKey;
    /**
     * Where a dead player's kept gear waits for their respawn.
     *
     * <p>On the player's own persistent data rather than a map in memory,
     * because a player who dies and quits without pressing respawn keeps their
     * gear waiting - and a map does not survive a restart, so the gear was
     * silently destroyed if the server went down in the meantime. Player data
     * is written on disconnect and reloaded on join, death screen or not, so
     * the stash now outlives both. It also means nothing accumulates for
     * players who never come back.
     */
    private final NamespacedKey pendingGearKey;
    /**
     * Same gear, held in memory for the ordinary case of dying and respawning
     * without leaving.
     *
     * <p>Not redundant: this is read first, so a respawn never depends on the
     * player's persistent data having survived the respawn itself (the entity
     * is rebuilt in the process). The stored copy above is what covers quitting
     * on the death screen and restarts; between them every route back is
     * covered, and both are cleared together.
     */
    private final Map<UUID, List<ItemStack>> pendingGear = new HashMap<>();
    // Stacks this tick's deaths are about to drop, each matched off as its
    // item entity spawns. Only ever holds the current tick's deaths.
    private final List<ItemStack> expectedDeathDrops = new ArrayList<>();
    private int expectedDeathDropsTick = -1;

    public DeathDropListener(Plugin plugin) {
        this.plugin = plugin;
        this.deathDropKey = new NamespacedKey(plugin, "death_drop");
        this.pendingGearKey = new NamespacedKey(plugin, "pending_gear");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        // keepLevel, if true, makes Bukkit silently reapply the player's
        // exact pre-death level/exp and ignore whatever newLevel/newExp are
        // set to below - it must be false for our reduced values to stick.
        // It also has no bearing on whether XP orbs spawn in the world;
        // that's shouldDropExperience, set separately here.
        event.setKeepLevel(false);
        event.setShouldDropExperience(false);
        event.setNewLevel(Math.max(0, player.getLevel() - LEVELS_LOST_ON_DEATH));
        event.setNewExp(0);

        List<ItemStack> drops = event.getDrops();
        List<ItemStack> kept = new ArrayList<>();

        Iterator<ItemStack> it = drops.iterator();
        while (it.hasNext()) {
            ItemStack stack = it.next();
            if (stack != null && isGear(stack.getType())) {
                kept.add(stack);
                it.remove();
            }
        }

        if (kept.isEmpty()) {
            return;
        }

        pendingGear.put(player.getUniqueId(), kept);
        try {
            player.getPersistentDataContainer().set(
                    pendingGearKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(kept));
        } catch (RuntimeException e) {
            // Kept in memory regardless, so this only costs the gear if the
            // player also leaves before respawning.
            plugin.getLogger().warning("Could not store " + player.getName() + "'s kept gear across a restart;"
                    + " it will be lost if they quit before respawning: " + e);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        restoreGear(event.getPlayer());
    }

    // Safety net for a stash that outlived the respawn it was waiting for -
    // e.g. the player respawned while this plugin was disabled, so nothing
    // handed it back. Only for a player who is actually alive: anything given
    // to a player still on the death screen is wiped by the respawn itself.
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.isDead()) {
            return;
        }
        restoreGear(player);
    }

    // Dropped from memory on the way out, since the stored copy is what serves
    // a player who left on the death screen - otherwise a stash for someone who
    // never returns would sit in this map for the rest of the server's life.
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingGear.remove(event.getPlayer().getUniqueId());
    }

    private void restoreGear(Player player) {
        List<ItemStack> cached = pendingGear.remove(player.getUniqueId());
        if (cached != null) {
            player.getPersistentDataContainer().remove(pendingGearKey);
            give(player, cached.toArray(new ItemStack[0]));
            return;
        }

        byte[] data = player.getPersistentDataContainer().get(pendingGearKey, PersistentDataType.BYTE_ARRAY);
        if (data == null) {
            return;
        }

        ItemStack[] kept;
        try {
            kept = ItemStack.deserializeItemsFromBytes(data);
        } catch (RuntimeException e) {
            // Left in place rather than cleared: a future version may be able
            // to read it, and clearing it is the one thing that cannot be
            // undone.
            plugin.getLogger().warning("Could not read " + player.getName() + "'s kept gear; leaving it stored: " + e);
            return;
        }

        // Cleared only once the items are safely in hand, so a failure part way
        // through can't consume the stash.
        player.getPersistentDataContainer().remove(pendingGearKey);
        give(player, kept);
    }

    // A tick later, because on respawn the inventory this writes into is still
    // being set up as the event fires.
    private void give(Player player, ItemStack[] items) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(items);
            for (ItemStack extra : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), extra);
            }
        });
    }

    // MONITOR so the list is final - after this listener's own gear removal
    // above and anything other plugins change. A cancelled death drops
    // nothing, so it's ignored.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeathDropsFinal(PlayerDeathEvent event) {
        int now = Bukkit.getCurrentTick();
        if (now != expectedDeathDropsTick) {
            expectedDeathDrops.clear();
            expectedDeathDropsTick = now;
        }
        for (ItemStack stack : event.getDrops()) {
            if (stack != null && !stack.isEmpty()) {
                expectedDeathDrops.add(stack.clone());
            }
        }
    }

    // Matching by exact stack rather than tagging whatever spawns this tick,
    // so an unrelated item that happens to drop in the same tick - a mob's
    // loot, a block broken nearby - doesn't get the extended lifetime too.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (Bukkit.getCurrentTick() != expectedDeathDropsTick || expectedDeathDrops.isEmpty()) {
            return;
        }
        Item item = event.getEntity();
        if (expectedDeathDrops.remove(item.getItemStack())) {
            item.getPersistentDataContainer().set(deathDropKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent event) {
        Item item = event.getEntity();
        if (!item.getPersistentDataContainer().has(deathDropKey, PersistentDataType.BYTE)) {
            return;
        }

        // First despawn attempt: cancel it and drop the marker. Vanilla resets
        // the item's age to 0 on a cancelled despawn, so the second attempt
        // comes one full despawn cycle later and goes through - doubling the
        // lifetime without a separate timer.
        item.getPersistentDataContainer().remove(deathDropKey);
        event.setCancelled(true);
    }

    private static boolean isGear(org.bukkit.Material material) {
        String name = material.name();
        return name.endsWith("_SWORD") || name.endsWith("_SPEAR") || name.endsWith("_PICKAXE")
                || name.endsWith("_AXE") || name.endsWith("_HOE") || name.endsWith("_SHOVEL")
                || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                || material == org.bukkit.Material.TRIDENT
                || material == org.bukkit.Material.BOW
                || material == org.bukkit.Material.ARROW
                || material == org.bukkit.Material.SPECTRAL_ARROW
                || material == org.bukkit.Material.TIPPED_ARROW
                || material == org.bukkit.Material.CROSSBOW
                || material == org.bukkit.Material.SHIELD
                || material == org.bukkit.Material.ELYTRA
                || material == org.bukkit.Material.MACE
                || material == org.bukkit.Material.FISHING_ROD
                || material == org.bukkit.Material.SPYGLASS
                || material == org.bukkit.Material.SHEARS
                || material == org.bukkit.Material.FLINT_AND_STEEL
                || material == org.bukkit.Material.BRUSH
                || material == org.bukkit.Material.CARROT_ON_A_STICK
                || material == org.bukkit.Material.WARPED_FUNGUS_ON_A_STICK;
    }
}
