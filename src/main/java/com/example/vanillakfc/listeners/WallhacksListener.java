package com.example.vanillakfc.listeners;

import com.example.vanillakfc.advancements.Advancements;
import com.example.vanillakfc.items.CustomItems;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;

/**
 * Awards the "Wallhacks" advancement for crafting the Warden Helmet.
 *
 * <p>Done in code rather than with a {@code recipe_crafted} criterion because
 * the helmet's recipe is registered by the plugin, after the datapack's
 * advancements have already loaded.
 *
 * <p>The craft event fires on the click, before vanilla decides whether the
 * result can actually go anywhere, so clicks that take nothing are skipped:
 * a cursor already holding something (the helmet doesn't stack onto it), or
 * a shift-click into a full inventory.
 */
public final class WallhacksListener implements Listener {

    private static final String ADVANCEMENT = "wallhacks";

    private final Advancements advancements;

    public WallhacksListener(Advancements advancements) {
        this.advancements = advancements;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!CustomItems.isWardenHelmet(event.getCurrentItem())) return;
        if (event.getAction() == InventoryAction.NOTHING) return;
        if (event.isShiftClick() && player.getInventory().firstEmpty() == -1) return;
        advancements.award(player, ADVANCEMENT);
    }
}
