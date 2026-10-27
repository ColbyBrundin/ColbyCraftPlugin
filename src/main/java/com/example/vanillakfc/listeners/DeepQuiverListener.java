package com.example.vanillakfc.listeners;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Every arrow fired from a Deep Quiver bow or crossbow behaves like an
 * Infinity arrow once it's in the air: it can never be picked back up,
 * whether it hits something or not. Deep Quiver's only effect on the
 * player's arrows is its chance not to use one up.
 *
 * <p>That chance is the bundled datapack's ({@code deep_quiver.json}, built
 * on vanilla's own ammo-use effect). Vanilla only makes an arrow
 * un-pickupable when no ammo was spent on it, so on its own the datapack left
 * every shot that did use an arrow free to be collected and fired again -
 * this closes that, giving every Deep Quiver shot Infinity's creative-only
 * pickup.
 *
 * <p>Fires for crossbows too: Paper raises the same event for each arrow a
 * crossbow lets go, Multishot's extra arrows included (already creative-only
 * in vanilla).
 */
public final class DeepQuiverListener implements Listener {

    private static final NamespacedKey DEEP_QUIVER_KEY = NamespacedKey.fromString("vanillakfc:deep_quiver");

    // Looked up on first use, and null if the datapack isn't loaded - then
    // there's no Deep Quiver to act on.
    private Enchantment deepQuiver;
    private boolean lookedUp;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow arrow)) return;
        if (arrow.getPickupStatus() != AbstractArrow.PickupStatus.ALLOWED) return;
        Enchantment enchantment = deepQuiver();
        ItemStack weapon = event.getBow();
        if (enchantment == null || weapon == null || weapon.getEnchantmentLevel(enchantment) <= 0) return;
        arrow.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
    }

    private Enchantment deepQuiver() {
        if (!lookedUp) {
            deepQuiver = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(DEEP_QUIVER_KEY);
            lookedUp = true;
        }
        return deepQuiver;
    }
}
