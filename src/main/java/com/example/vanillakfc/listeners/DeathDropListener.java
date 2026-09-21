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
 * On death, gear (weapons/tools/armor) stays with the player instead of
 * dropping, and XP level only drops by a flat amount (floored at 0) instead
 * of resetting to 0 or dropping as orbs; everything else drops as usual but
 * takes twice as long to despawn.
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
    private final Map<UUID, List<ItemStack>> pendingGear = new HashMap<>();
    // Stacks this tick's deaths are about to drop, each matched off as its
    // item entity spawns. Only ever holds the current tick's deaths.
    private final List<ItemStack> expectedDeathDrops = new ArrayList<>();
    private int expectedDeathDropsTick = -1;

    public DeathDropListener(Plugin plugin) {
        this.plugin = plugin;
        this.deathDropKey = new NamespacedKey(plugin, "death_drop");
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

        if (!kept.isEmpty()) {
            pendingGear.put(player.getUniqueId(), kept);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        List<ItemStack> kept = pendingGear.remove(player.getUniqueId());
        if (kept == null) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(kept.toArray(new ItemStack[0]));
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
                || material == org.bukkit.Material.CROSSBOW
                || material == org.bukkit.Material.SHIELD
                || material == org.bukkit.Material.ELYTRA
                || material == org.bukkit.Material.MACE;
    }
}
