package com.example.vanillakfc.listeners;

import org.bukkit.Tag;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps shulker boxes out of ender chests.
 *
 * <p>Only putting one in is refused. Shulker boxes already sitting in an
 * ender chest are left where they are and can be taken out as normal - they
 * just can't go back afterwards. Nothing is converted or removed.
 *
 * <p>A player's own clicks are the only way anything gets into an ender
 * chest (hoppers and droppers can't reach one), and a bundle can't hold a
 * shulker box, so the handlers below cover every route in.
 */
public final class EnderChestShulkerListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getType() != InventoryType.ENDER_CHEST) return;

        Inventory clicked = event.getClickedInventory();
        if (clicked == null) return;

        if (clicked != top) {
            // Shift-click sends the clicked stack up into the ender chest.
            if (event.isShiftClick() && isShulkerBox(event.getCurrentItem())) {
                event.setCancelled(true);
            }
            return;
        }

        ItemStack incoming;
        if (event.getClick() == ClickType.NUMBER_KEY) {
            incoming = event.getView().getBottomInventory().getItem(event.getHotbarButton());
        } else if (event.getClick() == ClickType.SWAP_OFFHAND) {
            incoming = event.getWhoClicked().getInventory().getItemInOffHand();
        } else {
            incoming = event.getCursor();
        }
        if (isShulkerBox(incoming)) {
            event.setCancelled(true);
        }
    }

    // Moving the mouse at all while clicking makes it a drag rather than a
    // click, even over a single slot, so this is as common a way in as the
    // click itself.
    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getType() != InventoryType.ENDER_CHEST) return;
        if (!isShulkerBox(event.getOldCursor())) return;

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private static boolean isShulkerBox(ItemStack item) {
        return item != null && Tag.SHULKER_BOXES.isTagged(item.getType());
    }
}
