package com.example.vanillakfc.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Doubles the cost of every villager trade that sells diamond tools, weapons
 * or armor - every ingredient's count, so emeralds and any diamonds a trade
 * also asks for alike.
 *
 * <p>New trades are doubled as a villager unlocks them, in
 * {@link VillagerAcquireTradeEvent}. Villagers that already had their trades
 * before this existed get a one-time pass instead: their existing diamond
 * trades are doubled once and the villager is marked, so no trade is ever
 * doubled twice. The pass runs for everything loaded at startup, for villagers
 * in chunks as they load, and - as a catch-all - just before a trade menu
 * opens. An unmarked villager unlocking a new trade is passed first, so the
 * new trade is doubled by the event alone rather than by the event and then
 * again by the pass.
 *
 * <p>Every copy is built from the trade's getters. Paper's
 * {@code MerchantRecipe(MerchantRecipe)} copy constructor reads the raw
 * fields instead, and the trades the server hands out keep their real values
 * elsewhere - those fields hold 0 max uses, so every copy it made was born
 * sold out, with no villager XP and no demand pricing either. Villagers that
 * went through that version are repaired as they are seen - see
 * {@link #repaired}.
 *
 * <p>Doubling works on the base price only. Vanilla's discounts (curing,
 * Hero of the Village, reputation) and demand surcharges are still applied on
 * top as usual, so they scale with it.
 */
public final class DiamondTradePriceListener implements Listener {

    private static final int PRICE_MULTIPLIER = 2;
    /** Most a doubled ingredient may cost - one full stack. */
    private static final int MAX_PRICE = 64;

    private static final Set<Material> DIAMOND_GEAR = EnumSet.of(
            Material.DIAMOND_SWORD, Material.DIAMOND_SPEAR, Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE,
            Material.DIAMOND_SHOVEL, Material.DIAMOND_HOE,
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS);

    // What vanilla gives every diamond gear trade, for rebuilding the ones
    // the old copy broke - read from this version's villager_trade data.
    private static final int VANILLA_MAX_USES = 3;
    private static final Set<Material> MASTER_TIER_GEAR = EnumSet.of(
            Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE);
    /** Enchanted gear and the plain hoe - one ingredient, emeralds only. */
    private static final float EMERALD_ONLY_PRICE_MULTIPLIER = 0.2F;
    /** trade_rebalance's armorer trades, which also take diamonds or diamond gear. */
    private static final float TWO_INGREDIENT_PRICE_MULTIPLIER = 0.05F;

    private final Plugin plugin;
    private final NamespacedKey migratedKey;

    public DiamondTradePriceListener(Plugin plugin) {
        this.plugin = plugin;
        this.migratedKey = new NamespacedKey(plugin, "diamond_trades_doubled");
    }

    /** One-time pass over every villager already loaded when the plugin starts. */
    public void migrateLoaded() {
        int migrated = 0;
        for (World world : Bukkit.getWorlds()) {
            for (AbstractVillager villager : world.getEntitiesByClass(AbstractVillager.class)) {
                if (migrate(villager)) migrated++;
            }
        }
        if (migrated > 0) {
            plugin.getLogger().info("Doubled diamond gear trade prices for " + migrated + " existing villager(s).");
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof AbstractVillager villager) {
                migrate(villager);
            }
        }
    }

    // LOWEST so the pass is done before anything else - the librarian
    // reconciliation included - looks at or rewrites the trade list.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof AbstractVillager villager) {
            migrate(villager);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAcquireTrade(VillagerAcquireTradeEvent event) {
        MerchantRecipe recipe = event.getRecipe();
        if (!isDiamondGear(recipe)) return;
        // The trade being acquired isn't in the villager's list yet, so the
        // pass can't touch it - it only settles the ones already there.
        migrate(event.getEntity());
        event.setRecipe(doubled(recipe));
    }

    /** @return true if this call is what migrated the villager */
    private boolean migrate(AbstractVillager villager) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        if (pdc.has(migratedKey, PersistentDataType.BYTE)) {
            repairBroken(villager);
            return false;
        }

        List<MerchantRecipe> recipes = villager.getRecipes();
        for (int i = 0; i < recipes.size(); i++) {
            MerchantRecipe recipe = recipes.get(i);
            if (isDiamondGear(recipe)) {
                villager.setRecipe(i, doubled(recipe));
            }
        }
        pdc.set(migratedKey, PersistentDataType.BYTE, (byte) 1);
        return true;
    }

    // Vanilla never makes a trade with 0 max uses, so that alone marks one the
    // old copy broke - and a repaired trade has 3, so this is safe to run
    // every time and does nothing once there's nothing left to fix. The price
    // itself came through the old copy fine and is kept as it is.
    private void repairBroken(AbstractVillager villager) {
        List<MerchantRecipe> recipes = villager.getRecipes();
        for (int i = 0; i < recipes.size(); i++) {
            MerchantRecipe recipe = recipes.get(i);
            if (isDiamondGear(recipe) && recipe.getMaxUses() <= 0) {
                villager.setRecipe(i, repaired(recipe));
            }
        }
    }

    // Rebuilt with vanilla's values, since the broken trade no longer carries
    // its own. Villager XP goes by what the item is: the master-tier pieces
    // 30, the rest 15, and the plain hoe 10. A few trade_rebalance armorer
    // trades differ by tier from that (e.g. Taiga's level-4 helmet is 15),
    // which only affects how fast the villager levels, not the trade itself.
    private static MerchantRecipe repaired(MerchantRecipe recipe) {
        Material type = recipe.getResult().getType();
        int villagerXp = type == Material.DIAMOND_HOE ? 10 : MASTER_TIER_GEAR.contains(type) ? 30 : 15;
        float priceMultiplier = recipe.getIngredients().size() > 1
                ? TWO_INGREDIENT_PRICE_MULTIPLIER : EMERALD_ONLY_PRICE_MULTIPLIER;
        MerchantRecipe fixed = new MerchantRecipe(recipe.getResult(), 0, VANILLA_MAX_USES, true,
                villagerXp, priceMultiplier, 0, 0, recipe.shouldIgnoreDiscounts());
        fixed.setIngredients(recipe.getIngredients());
        return fixed;
    }

    private static boolean isDiamondGear(MerchantRecipe recipe) {
        return DIAMOND_GEAR.contains(recipe.getResult().getType());
    }

    private MerchantRecipe doubled(MerchantRecipe recipe) {
        List<ItemStack> ingredients = new ArrayList<>();
        for (ItemStack ingredient : recipe.getIngredients()) {
            if (ingredient == null || ingredient.isEmpty()) continue;
            ItemStack copy = ingredient.clone();
            // Capped per ingredient, so a price that was already over half
            // the cap only rises to it rather than past it.
            copy.setAmount(Math.min(ingredient.getAmount() * PRICE_MULTIPLIER, MAX_PRICE));
            ingredients.add(copy);
        }

        // Not new MerchantRecipe(recipe) - see the class comment.
        MerchantRecipe copy = new MerchantRecipe(recipe.getResult(), recipe.getUses(), recipe.getMaxUses(),
                recipe.hasExperienceReward(), recipe.getVillagerExperience(), recipe.getPriceMultiplier(),
                recipe.getDemand(), recipe.getSpecialPrice(), recipe.shouldIgnoreDiscounts());
        copy.setIngredients(ingredients);
        return copy;
    }
}
