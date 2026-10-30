package com.example.vanillakfc.listeners;

import com.example.vanillakfc.items.CustomItems;
import com.example.vanillakfc.recipes.CustomRecipes;
import org.bukkit.Keyed;
import org.bukkit.block.Crafter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.Objects;

/**
 * Keeps custom items from being used as the vanilla item they're built on.
 *
 * <p>Vanilla recipes match on item type alone, so a Warden Heart (a Nether
 * Star underneath) would otherwise craft a beacon, and two Warden Helmets
 * in the crafting grid would "repair" into a plain netherite helmet. Any
 * craft with a custom item in it is refused unless the recipe was made for
 * it ({@link CustomRecipes#acceptsCustomItems}). The same goes for the
 * grindstone, where merging two items keeps the first one's identity: a
 * custom item only merges with another of its own kind. A lone custom item
 * there just has its enchantments taken off, as normal. Anvils are handled
 * in {@link AnvilRepairListener}, alongside the rest of the anvil rules.
 */
public final class CustomItemGuardListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!allowed(event.getRecipe(), event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (!(event.getBlock().getState(false) instanceof Crafter crafter)) return;
        if (!allowed(event.getRecipe(), crafter.getInventory().getContents())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        ItemStack upper = event.getInventory().getUpperItem();
        ItemStack lower = event.getInventory().getLowerItem();
        if (upper == null || upper.isEmpty() || lower == null || lower.isEmpty()) return;
        String upperId = CustomItems.id(upper);
        String lowerId = CustomItems.id(lower);
        if ((upperId != null || lowerId != null) && !Objects.equals(upperId, lowerId)) {
            event.setResult(null);
        }
    }

    private static boolean allowed(Recipe recipe, ItemStack[] grid) {
        if (recipe == null) return true;
        boolean hasCustom = false;
        for (ItemStack item : grid) {
            if (CustomItems.isCustom(item)) {
                hasCustom = true;
                break;
            }
        }
        return !hasCustom || (recipe instanceof Keyed keyed && CustomRecipes.acceptsCustomItems(keyed.getKey()));
    }
}
