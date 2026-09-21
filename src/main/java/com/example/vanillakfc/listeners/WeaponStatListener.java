package com.example.vanillakfc.listeners;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Collection;
import java.util.Map;

public final class WeaponStatListener implements Listener {

    private record WeaponStats(double damage, double speed) {}

    // When any custom attribute modifier is written to an item, Minecraft's
    // attribute_modifiers component replaces ALL type-level defaults — so both
    // damage and speed must be specified explicitly even when only one changes.
    // damage = total attack damage; speed = attacks per second
    private static final Map<Material, WeaponStats> STAT_OVERRIDES = Map.ofEntries(
        // Modified copper/stone/iron tools
        Map.entry(Material.COPPER_AXE,        new WeaponStats(7.0, 0.9)),
        Map.entry(Material.IRON_AXE,          new WeaponStats(7.0, 1.0)),
        Map.entry(Material.IRON_HOE,          new WeaponStats(1.0, 2.0)),
        // Modified stone tools
        Map.entry(Material.STONE_AXE,         new WeaponStats(7.0, 0.9)),
        // Diamond: +1 damage, speed unchanged
        Map.entry(Material.DIAMOND_SWORD,     new WeaponStats(8.0,  1.6)),
        Map.entry(Material.DIAMOND_PICKAXE,   new WeaponStats(6.0,  1.2)),
        Map.entry(Material.DIAMOND_SHOVEL,    new WeaponStats(6.5,  1.0)),
        Map.entry(Material.DIAMOND_SPEAR,     new WeaponStats(5.0,  0.95)),
        // Netherite: +1 damage, speed unchanged
        Map.entry(Material.NETHERITE_SWORD,   new WeaponStats(9.0,  1.6)),
        Map.entry(Material.NETHERITE_PICKAXE, new WeaponStats(7.0,  1.2)),
        Map.entry(Material.NETHERITE_SHOVEL,  new WeaponStats(7.5,  1.0)),
        Map.entry(Material.NETHERITE_SPEAR,   new WeaponStats(6.0,  0.87))
    );

    // Vanilla renders a tool's OWN combat stats specially - green, unsigned,
    // damage listed above speed - by recognizing these exact modifier IDs as
    // "this item's definitive base stat" rather than a generic bonus (which
    // renders blue/signed, in whatever order it was added). Reusing
    // vanilla's own IDs instead of a plugin-namespaced key gets that
    // treatment for free rather than trying to fake vanilla's look with
    // hidden attributes + hand-styled lore. Recognizing this ID also
    // switches the tooltip (and, it appears, the actual displayed total)
    // back to showing player-base-plus-modifier rather than the raw
    // modifier alone, so the amount stored here has to go back to being a
    // delta against the real 1.0/4.0 player base - confirmed empirically
    // after the switch: storing the full intended value instead of a delta
    // rendered every tool exactly 1.0 damage / 4.0 speed too high.
    private static final double PLAYER_BASE_ATTACK_DAMAGE = 1.0;
    private static final double PLAYER_BASE_ATTACK_SPEED = 4.0;

    private final NamespacedKey damageKey = NamespacedKey.minecraft("base_attack_damage");
    private final NamespacedKey speedKey = NamespacedKey.minecraft("base_attack_speed");

    private boolean applyStats(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        WeaponStats stats = STAT_OVERRIDES.get(item.getType());
        if (stats == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        // Checked against the actual stored modifier amounts rather than a
        // separate "already applied" flag, so a tool that was tagged under
        // an earlier, buggy version of this formula (or a future tuning
        // change) gets corrected automatically instead of being stuck with
        // whatever it happened to get the first time.
        if (alreadyCorrect(meta, stats)) return false;

        // Rebuild the whole attribute-modifiers component wholesale rather
        // than remove-then-add: this item's default ATTACK_DAMAGE/
        // ATTACK_SPEED modifiers may live on its vanilla item-type
        // prototype rather than as an explicit per-item override, in which
        // case removeAttributeModifier has nothing to remove and the
        // vanilla default keeps applying alongside whatever we add on top
        // (observed: a stone axe showing -3.4 attack speed instead of the
        // intended -3.1). Copying every OTHER existing modifier across and
        // only rebuilding these two attributes guarantees nothing from the
        // item's prior state - vanilla default or otherwise - can coexist
        // with ours.
        Multimap<Attribute, AttributeModifier> modifiers = ArrayListMultimap.create();
        if (meta.hasAttributeModifiers()) {
            for (Map.Entry<Attribute, AttributeModifier> entry : meta.getAttributeModifiers().entries()) {
                if (entry.getKey() != Attribute.ATTACK_DAMAGE && entry.getKey() != Attribute.ATTACK_SPEED) {
                    modifiers.put(entry.getKey(), entry.getValue());
                }
            }
        }
        modifiers.put(Attribute.ATTACK_DAMAGE, new AttributeModifier(
            damageKey,
            damageModifierAmount(stats),
            AttributeModifier.Operation.ADD_NUMBER,
            EquipmentSlotGroup.MAINHAND));
        modifiers.put(Attribute.ATTACK_SPEED, new AttributeModifier(
            speedKey,
            speedModifierAmount(stats),
            AttributeModifier.Operation.ADD_NUMBER,
            EquipmentSlotGroup.MAINHAND));
        meta.setAttributeModifiers(modifiers);

        item.setItemMeta(meta);
        return true;
    }

    private double damageModifierAmount(WeaponStats stats) {
        return stats.damage() - PLAYER_BASE_ATTACK_DAMAGE;
    }

    private double speedModifierAmount(WeaponStats stats) {
        return stats.speed() - PLAYER_BASE_ATTACK_SPEED;
    }

    private boolean alreadyCorrect(ItemMeta meta, WeaponStats stats) {
        return hasExactModifier(meta, Attribute.ATTACK_DAMAGE, damageKey, damageModifierAmount(stats))
                && hasExactModifier(meta, Attribute.ATTACK_SPEED, speedKey, speedModifierAmount(stats));
    }

    private boolean hasExactModifier(ItemMeta meta, Attribute attribute, NamespacedKey key, double amount) {
        Collection<AttributeModifier> existing = meta.getAttributeModifiers(attribute);
        if (existing == null) return false;
        for (AttributeModifier modifier : existing) {
            if (modifier.getKey().equals(key) && modifier.getAmount() == amount) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        ItemStack result = event.getCurrentItem();
        if (applyStats(result)) {
            event.getInventory().setResult(result);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (applyStats(item)) {
            event.getItem().setItemStack(item);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (applyStats(contents[i])) {
                inv.setItem(i, contents[i]);
            }
        }
    }
}
