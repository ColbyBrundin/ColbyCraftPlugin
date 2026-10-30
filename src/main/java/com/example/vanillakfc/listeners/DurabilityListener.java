package com.example.vanillakfc.listeners;

import com.example.vanillakfc.items.CustomItems;
import io.papermc.paper.event.entity.EntityDamageItemEvent;
import org.bukkit.Material;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;

public final class DurabilityListener implements Listener {

    private static final Map<Material, Integer> CUSTOM_DURABILITY = Map.ofEntries(
        // Iron tools
        Map.entry(Material.IRON_SWORD,      400),
        Map.entry(Material.IRON_PICKAXE,    400),
        Map.entry(Material.IRON_AXE,        400),
        Map.entry(Material.IRON_SHOVEL,     400),
        Map.entry(Material.IRON_HOE,        400),
        // Iron armor
        Map.entry(Material.IRON_HELMET,     240),
        Map.entry(Material.IRON_CHESTPLATE, 330),
        Map.entry(Material.IRON_LEGGINGS,   310),
        Map.entry(Material.IRON_BOOTS,      275),
        // Diamond tools
        Map.entry(Material.DIAMOND_SWORD,      2000),
        Map.entry(Material.DIAMOND_PICKAXE,    2000),
        Map.entry(Material.DIAMOND_AXE,        2000),
        Map.entry(Material.DIAMOND_SHOVEL,     2000),
        Map.entry(Material.DIAMOND_HOE,        2000),
        // Diamond armor
        Map.entry(Material.DIAMOND_HELMET,     500),
        Map.entry(Material.DIAMOND_CHESTPLATE, 700),
        Map.entry(Material.DIAMOND_LEGGINGS,   650),
        Map.entry(Material.DIAMOND_BOOTS,      575),
        // Netherite tools and weapons
        Map.entry(Material.NETHERITE_SWORD,    4000),
        Map.entry(Material.NETHERITE_SPEAR,    4000),
        Map.entry(Material.NETHERITE_PICKAXE,  4000),
        Map.entry(Material.NETHERITE_AXE,      4000),
        Map.entry(Material.NETHERITE_SHOVEL,   4000),
        Map.entry(Material.NETHERITE_HOE,      4000),
        // Netherite armor
        Map.entry(Material.NETHERITE_HELMET,     850),
        Map.entry(Material.NETHERITE_CHESTPLATE, 1000),
        Map.entry(Material.NETHERITE_LEGGINGS,   950),
        Map.entry(Material.NETHERITE_BOOTS,      900),
        // Trident - not a tier item, so it sits on its own
        Map.entry(Material.TRIDENT,            400),
        // Wolf armor (vanilla 64). Its cracking stages are fractions of max
        // durability - 95%, 69% and 32% remaining - and an Armadillo Scute
        // repairs 12.5% of it, so all of those scale with this on their own.
        Map.entry(Material.WOLF_ARMOR,         128)
    );

    @EventHandler
    public void onItemDamage(PlayerItemDamageEvent event) {
        applyDurability(event.getItem());
    }

    // Wolf armor wears out on the wolf, which goes through the mob event
    // rather than the player one. A fallback here - see onWolfDamaged.
    @EventHandler
    public void onMobItemDamage(EntityDamageItemEvent event) {
        applyDurability(event.getItem());
    }

    // Wolf armor is converted before the hit reaches it, not as it takes the
    // damage: vanilla notes the armor's cracking stage from its old max
    // durability just before damaging it, and compares that with the stage
    // after. Raising the max in between would make the first hit on
    // unconverted armor play the crack sound and particles even though it
    // got further from cracking, not closer.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWolfDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Wolf wolf)) return;
        EntityEquipment equipment = wolf.getEquipment();
        ItemStack armor = equipment.getItem(EquipmentSlot.BODY);
        if (armor.getType() != Material.WOLF_ARMOR) return;
        if (applyDurability(armor)) {
            // Silent, so putting the converted piece back doesn't play the
            // armor-equip sound.
            equipment.setItem(EquipmentSlot.BODY, armor, true);
        }
    }

    /**
     * Also used by {@link PetHealthDisplayListener}, so a wolf's armor counter
     * shows the new maximum from the start rather than vanilla's until the
     * wolf's first hit.
     *
     * @return true if the item was changed
     */
    static boolean applyDurability(ItemStack item) {
        // Custom items set their own (the Warden Helmet is a netherite helmet
        // with 600, not netherite's 850).
        if (CustomItems.isCustom(item)) return false;
        Integer target = CUSTOM_DURABILITY.get(item.getType());
        if (target == null) return false;

        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return false;
        // getMaxDamage() throws unless hasMaxDamage() is true (only set once
        // something explicitly overrides it - most items never have it set).
        if (damageable.hasMaxDamage() && damageable.getMaxDamage() == target) return false;

        damageable.setMaxDamage(target);
        item.setItemMeta(meta);
        return true;
    }
}
