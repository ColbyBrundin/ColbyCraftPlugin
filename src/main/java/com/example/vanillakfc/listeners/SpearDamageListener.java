package com.example.vanillakfc.listeners;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.EnumSet;
import java.util.Set;

/**
 * Caps the Spear's charge-attack bonus - extra damage scaled by the relative
 * velocity between attacker and target, which is what makes falling onto
 * one (or being charged by a fast-moving target) hit so hard - at 5x whatever
 * the same swing would have dealt without it.
 *
 * <p>Bukkit's pre-mitigation {@code DamageModifier#BASE} already has the
 * charge bonus folded into one lump sum by the time this event fires, the
 * same way it folds in the Mace's fall-distance bonus (see
 * {@link MaceDamageListener}) - there's no API to read the charge component
 * in isolation. So instead of isolating it, this independently recomputes
 * what a non-charge hit would deal - the attacker's current total
 * {@code ATTACK_DAMAGE} attribute (player base + whatever the equipped
 * spear's tier/custom stats add) plus the standard Sharpness bonus - and
 * clamps BASE to 5x that if the actual hit came in higher.
 *
 * <p>Only Sharpness is accounted for in the enchant-bonus half of that
 * reference figure; Smite/Bane of Arthropods (situational, target-type-
 * gated bonuses) aren't, so a Smite/Bane spear's effective cap against its
 * favored target type is a little tighter than fully vanilla-accurate.
 */
public final class SpearDamageListener implements Listener {

    private static final Set<Material> SPEARS = EnumSet.of(
            Material.WOODEN_SPEAR, Material.STONE_SPEAR, Material.COPPER_SPEAR,
            Material.IRON_SPEAR, Material.GOLDEN_SPEAR, Material.DIAMOND_SPEAR, Material.NETHERITE_SPEAR
    );

    private static final double DAMAGE_CAP_MULTIPLIER = 5.0;

    // DamageModifier is deprecated but remains the only API that exposes
    // pre-mitigation damage (same justification as MaceDamageListener).
    @SuppressWarnings("deprecation")
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;

        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!SPEARS.contains(weapon.getType())) return;

        double cap = referenceDamage(player, weapon) * DAMAGE_CAP_MULTIPLIER;
        if (event.getDamage(DamageModifier.BASE) > cap) {
            event.setDamage(DamageModifier.BASE, cap);
        }
    }

    // What this hit would deal with no charge-velocity bonus: the
    // attacker's current total attack damage attribute (player base +
    // whatever the equipped spear's tier/custom stats add, matching
    // WeaponStatListener's overrides where present) plus Sharpness's flat
    // bonus, if enchanted.
    private double referenceDamage(Player player, ItemStack weapon) {
        AttributeInstance attackDamage = player.getAttribute(Attribute.ATTACK_DAMAGE);
        double base = attackDamage != null ? attackDamage.getValue() : 1.0;

        ItemMeta meta = weapon.getItemMeta();
        int sharpnessLevel = meta != null ? meta.getEnchantLevel(Enchantment.SHARPNESS) : 0;
        double sharpnessBonus = sharpnessLevel > 0 ? 0.5 * sharpnessLevel + 0.5 : 0.0;

        return base + sharpnessBonus;
    }
}
