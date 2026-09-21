package com.example.vanillakfc.listeners;

import com.example.vanillakfc.recipes.CustomRecipes;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Unlocks this plugin's recipes in the recipe book as soon as a player is
 * holding what they're made from, the way vanilla's own "Recipe Unlocked"
 * toasts behave.
 *
 * <p>Vanilla drives that off an advancement generated per recipe, and those
 * only exist for the {@code minecraft:}-keyed recipes shipped with the game.
 * Anything registered under a plugin's own namespace - here, the chainmail
 * set and every {@code _block_variant_} arrangement - has no such
 * advancement, so the client is never told the recipe exists. It stays
 * missing from the book until the player happens to craft it by hand, which
 * unlocks it as a side effect; that's the "it only shows up after making
 * one" behaviour this closes off.
 *
 * <p>Tracked per player rather than globally, since discovery is individual
 * progress - it lives in each player's own save data.
 */
public final class RecipeDiscoveryListener implements Listener {

    private final Plugin plugin;
    private final Map<UUID, Set<NamespacedKey>> undiscovered = new HashMap<>();
    private final Set<UUID> sweepScheduled = new HashSet<>();

    public RecipeDiscoveryListener(Plugin plugin) {
        this.plugin = plugin;
    }

    // Seeds anyone already online, so a /reload or a mid-session enable
    // doesn't leave existing players waiting until their next login.
    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            track(player);
        }
    }

    public void stop() {
        undiscovered.clear();
        sweepScheduled.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        track(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        undiscovered.remove(playerId);
        sweepScheduled.remove(playerId);
    }

    // This fires once per changed slot, so a single craft, shift-click or
    // chest transfer can produce a burst of them. Collapse the whole burst
    // into one sweep next tick rather than rescanning the inventory once
    // per individual slot.
    @EventHandler
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        Set<NamespacedKey> remaining = undiscovered.get(playerId);
        if (remaining == null || remaining.isEmpty() || !sweepScheduled.add(playerId)) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            sweepScheduled.remove(playerId);
            if (player.isOnline()) {
                sweep(player);
            }
        });
    }

    // Crafting a recipe normally unlocks it. For a hidden alternate that
    // would quietly add the very duplicate book entry the consolidation
    // exists to prevent, so the unlock is refused - the recipe stays fully
    // craftable, it just never surfaces in the book.
    @EventHandler(ignoreCancelled = true)
    public void onRecipeDiscover(PlayerRecipeDiscoverEvent event) {
        if (CustomRecipes.getHiddenAlternates().contains(event.getRecipe())) {
            event.setCancelled(true);
        }
    }

    private void track(Player player) {
        Set<NamespacedKey> hidden = CustomRecipes.getHiddenAlternates();

        // Anyone who played on a build that registered every arrangement as
        // its own visible entry still carries those in their book; clear them
        // so the duplicates actually disappear rather than lingering forever
        // on existing players.
        List<NamespacedKey> staleAlternates = new ArrayList<>();
        for (NamespacedKey key : hidden) {
            if (player.hasDiscoveredRecipe(key)) {
                staleAlternates.add(key);
            }
        }
        if (!staleAlternates.isEmpty()) {
            player.undiscoverRecipes(staleAlternates);
        }

        Set<NamespacedKey> remaining = new HashSet<>(CustomRecipes.getRegisteredRecipes().keySet());
        remaining.removeAll(hidden);
        remaining.removeAll(player.getDiscoveredRecipes());
        undiscovered.put(player.getUniqueId(), remaining);
        sweep(player);
    }

    private void sweep(Player player) {
        Set<NamespacedKey> remaining = undiscovered.get(player.getUniqueId());
        if (remaining == null || remaining.isEmpty()) {
            return;
        }

        ItemStack[] contents = player.getInventory().getContents();
        Map<NamespacedKey, List<RecipeChoice>> registered = CustomRecipes.getRegisteredRecipes();
        List<NamespacedKey> unlocked = new ArrayList<>();

        for (Iterator<NamespacedKey> it = remaining.iterator(); it.hasNext(); ) {
            NamespacedKey key = it.next();
            List<RecipeChoice> ingredients = registered.get(key);
            if (ingredients == null || !holdsAll(contents, ingredients)) {
                continue;
            }
            unlocked.add(key);
            it.remove();
        }

        if (!unlocked.isEmpty()) {
            player.discoverRecipes(unlocked);
        }
    }

    // Presence of each distinct ingredient is enough - deliberately not
    // quantity. Vanilla's recipe advancements unlock off simply obtaining
    // an ingredient too, so requiring the full count (three iron blocks for
    // a pickaxe, say) would hide recipes vanilla would already be showing.
    private static boolean holdsAll(ItemStack[] contents, List<RecipeChoice> ingredients) {
        for (RecipeChoice ingredient : ingredients) {
            boolean found = false;
            for (ItemStack stack : contents) {
                if (stack != null && ingredient.test(stack)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
