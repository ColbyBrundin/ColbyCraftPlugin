package com.example.vanillakfc.items;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.Repairable;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.set.RegistrySet;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The plugin's custom items. Each is a vanilla item with its look, name and
 * stats replaced through data components, and tagged with
 * {@code vanillakfc:custom_item} so it can be told apart from the plain item
 * underneath - recipes, anvils and the rest only see the base type.
 *
 * <p>Looks come from the plugin's resource pack: {@code item_model} names
 * {@code assets/vanillakfc/items/<id>.json}, and the helmet's worn texture
 * is {@code assets/vanillakfc/equipment/warden.json}, both drawing on the
 * pack's own textures. Items only carry those names, not the art, so new
 * textures only need the pack changing - items already made pick them up.
 */
public final class CustomItems {

    public static final String WARDEN_HEART = "warden_heart";
    public static final String WARDEN_HELMET = "warden_helmet";

    public static final int WARDEN_HELMET_DURABILITY = 600;

    private static final String NAMESPACE = "vanillakfc";
    private static final NamespacedKey ID_KEY = new NamespacedKey(NAMESPACE, "custom_item");

    private CustomItems() {
    }

    /**
     * Dropped by the Warden. A Nether Star underneath: no use of its own
     * beyond the beacon recipe (which {@code CustomItemGuardListener} blocks
     * for it), and its item entity already shrugs off explosions.
     */
    public static ItemStack wardenHeart() {
        ItemStack item = ItemStack.of(Material.NETHER_STAR);
        item.setData(DataComponentTypes.ITEM_NAME, Component.text("Warden Heart", NamedTextColor.DARK_AQUA));
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key(NAMESPACE, WARDEN_HEART));
        item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false);
        item.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                lore("Still beating."),
                lore("Crafts the Warden Helmet."))));
        tag(item, WARDEN_HEART);
        return item;
    }

    /**
     * A Netherite Helmet underneath, so it goes on the head, takes every
     * helmet enchantment and survives lava like netherite - but with its own
     * armor values, durability and repair material in place of netherite's.
     * The effects are {@code WardenHelmetListener}'s.
     */
    public static ItemStack wardenHelmet() {
        ItemStack item = ItemStack.of(Material.NETHERITE_HELMET);
        item.setData(DataComponentTypes.ITEM_NAME, Component.text("Warden Helmet", NamedTextColor.DARK_AQUA));
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key(NAMESPACE, WARDEN_HELMET));
        item.setData(DataComponentTypes.MAX_DAMAGE, WARDEN_HELMET_DURABILITY);
        // Replaces netherite's set outright, knockback resistance included.
        item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.itemAttributes()
                .addModifier(Attribute.ARMOR, new AttributeModifier(
                        new NamespacedKey(NAMESPACE, "warden_helmet_armor"), 2.0,
                        AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD))
                .addModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(
                        new NamespacedKey(NAMESPACE, "warden_helmet_toughness"), 1.0,
                        AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD))
                .build());
        item.setData(DataComponentTypes.REPAIRABLE, Repairable.repairable(RegistrySet.keySet(RegistryKey.ITEM,
                TypedKey.create(RegistryKey.ITEM, Key.key("minecraft", "echo_shard")))));
        item.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.HEAD)
                .assetId(Key.key(NAMESPACE, "warden"))
                .equipSound(Key.key("minecraft", "item.armor.equip_netherite"))
                .build());
        item.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                lore("Hostile mobs within 20 blocks glow,"),
                lore("seen only by you, and their footsteps"),
                lore("carry further."),
                lore("Below Y=0 in the Overworld: Resistance."))));
        tag(item, WARDEN_HELMET);
        return item;
    }

    /**
     * Every custom item by id, for {@code /customgive}. Built fresh on each
     * call, so callers can change the stack they get.
     */
    public static final Map<String, Supplier<ItemStack>> ALL = Map.of(
            WARDEN_HEART, CustomItems::wardenHeart,
            WARDEN_HELMET, CustomItems::wardenHelmet);

    public static String namespace() {
        return NAMESPACE;
    }

    /** The custom item id, or null for anything that isn't one. */
    public static String id(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        return item.getPersistentDataContainer().get(ID_KEY, PersistentDataType.STRING);
    }

    public static boolean isCustom(ItemStack item) {
        return id(item) != null;
    }

    public static boolean isWardenHeart(ItemStack item) {
        return WARDEN_HEART.equals(id(item));
    }

    public static boolean isWardenHelmet(ItemStack item) {
        return WARDEN_HELMET.equals(id(item));
    }

    private static void tag(ItemStack item, String id) {
        item.editPersistentDataContainer(pdc -> pdc.set(ID_KEY, PersistentDataType.STRING, id));
    }

    // Lore is italic by default; plain grey reads as a description.
    private static Component lore(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }
}
