package com.example.vanillakfc.listeners;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.keys.tags.EnchantmentTagKeys;
import org.bukkit.FeatureFlag;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fully replaces vanilla's librarian trade generation for levels 1-4
 * (Novice through Expert) with a plugin-defined pool. None of this depends
 * on a bundled datapack - it behaves identically on any server the plugin is
 * dropped into, with zero setup. (Trade values below - prices, item counts,
 * max uses - are matched against this version's actual vanilla trade data,
 * not guessed.)
 *
 * <p>There's no separate "Master" tier mechanic. Each biome's strongest
 * enchant (what vanilla's own trade_rebalance calls the Master-tier enchant,
 * e.g. Efficiency III for Desert) is just one more entry in that biome's
 * Journeyman pool, picked with the same even odds as every other entry in
 * it - see {@link #COMMON_ENCHANTS}. It keeps its fixed level and vanilla's
 * own higher master-tier price formula when picked; every other entry in
 * the pool still rolls a random level 1-2 at 5-32 emeralds. Level 5
 * (Master) itself doesn't add or change anything - whatever book a
 * librarian rolled at level 3 is the one it keeps.
 *
 * <p>The world's own minecraft:trade_rebalance feature flag is read (not
 * written) as a deliberate on/off switch for this biome flavor: with it
 * enabled, the enchant is drawn from the biome-specific pool described
 * above; with it disabled, it's drawn uniformly from every enchantment an
 * enchanting table could give (the server's own
 * {@code #minecraft:in_enchanting_table}), at the same level 1-2 and 5-32
 * emeralds as the biome pools' regular entries - no biome flavor, no
 * fixed-level entries, same as vanilla's non-biome-specific trades.
 *
 * <p>Vanilla's own trade-unlock pipeline still fires as a librarian levels
 * up. {@link #onVillagerAcquireTrade} cancels any enchanted-book trade it
 * tries to hand out - the one kind of trade that can't just be topped up
 * afterward without risking an off-pool or duplicate result - while
 * {@link #onInteract} reconciles a librarian's full trade list against this
 * class's pools every time a player opens their trade menu, adding
 * whatever's missing for the villager's current level and enforcing the
 * fixed-or-choice trades at each level.
 */
public final class VillagerTradeListener implements Listener {

    private record EnchantSpec(Enchantment enchantment, int level) {}

    // level == RANDOM_LEVEL means "roll a random level (1..min(maxLevel,2))
    // and the plain 5-32 emerald price when picked" instead of using a fixed
    // level/price - see rolled() below. Every entry is one of those except
    // the one fixed, former-"master" enchant folded into each biome's list.
    private static final int RANDOM_LEVEL = 0;

    private static EnchantSpec rolled(Enchantment enchantment) {
        return new EnchantSpec(enchantment, RANDOM_LEVEL);
    }

    // Each biome's regular flavor enchants, plus that biome's former
    // "master"-tier enchant folded in as one more equally-likely pool entry
    // (fixed level; Swamp's is Thorns 2, this listener's own override of
    // vanilla's Mending - the other six match vanilla's own trade_rebalance
    // master enchant for that biome). No curse enchantments in any pool.
    private static final Map<Villager.Type, List<EnchantSpec>> COMMON_ENCHANTS = Map.of(
            Villager.Type.DESERT, List.of(
                    rolled(Enchantment.FIRE_PROTECTION), rolled(Enchantment.THORNS), rolled(Enchantment.INFINITY),
                    new EnchantSpec(Enchantment.EFFICIENCY, 3)),
            Villager.Type.JUNGLE, List.of(
                    rolled(Enchantment.FEATHER_FALLING), rolled(Enchantment.PROJECTILE_PROTECTION), rolled(Enchantment.POWER),
                    new EnchantSpec(Enchantment.UNBREAKING, 2)),
            Villager.Type.PLAINS, List.of(
                    rolled(Enchantment.PUNCH), rolled(Enchantment.SMITE), rolled(Enchantment.BANE_OF_ARTHROPODS),
                    new EnchantSpec(Enchantment.PROTECTION, 3)),
            Villager.Type.SAVANNA, List.of(
                    rolled(Enchantment.KNOCKBACK), rolled(Enchantment.SWEEPING_EDGE),
                    new EnchantSpec(Enchantment.SHARPNESS, 3)),
            Villager.Type.SNOW, List.of(
                    rolled(Enchantment.AQUA_AFFINITY), rolled(Enchantment.LOOTING), rolled(Enchantment.FROST_WALKER),
                    new EnchantSpec(Enchantment.SILK_TOUCH, 1)),
            Villager.Type.SWAMP, List.of(
                    rolled(Enchantment.DEPTH_STRIDER), rolled(Enchantment.RESPIRATION),
                    new EnchantSpec(Enchantment.THORNS, 2)),
            Villager.Type.TAIGA, List.of(
                    rolled(Enchantment.BLAST_PROTECTION), rolled(Enchantment.FIRE_ASPECT), rolled(Enchantment.FLAME),
                    new EnchantSpec(Enchantment.FORTUNE, 2))
    );

    private static final Set<Enchantment> CURSE_ENCHANTMENTS =
            Set.of(Enchantment.BINDING_CURSE, Enchantment.VANISHING_CURSE);

    private final NamespacedKey journeymanSideKey;
    private final NamespacedKey expertSideKey;
    /**
     * Pool used with trade_rebalance disabled, and for the one case a villager
     * type isn't one of the seven keys above (shouldn't happen - Villager.Type
     * only has these seven - but getOrDefault needs somewhere safe to land).
     *
     * <p>Read from the server's own {@code #minecraft:in_enchanting_table}
     * rather than written out here, so it is exactly what an enchanting table
     * could give - Mending and the curses are treasure enchantments and so
     * already absent, no exclusion list needed - and it can't fall behind a
     * new enchantment the way a hand-kept list did (it was still missing
     * Breach, Density and Lunge, and still carried Frost Walker, which an
     * enchanting table has never offered).
     */
    private final List<EnchantSpec> genericPool;

    public VillagerTradeListener(Plugin plugin) {
        this.journeymanSideKey = new NamespacedKey(plugin, "journeyman_side");
        this.expertSideKey = new NamespacedKey(plugin, "expert_side");
        this.genericPool = buildGenericPool(plugin);
    }

    private static List<EnchantSpec> buildGenericPool(Plugin plugin) {
        Registry<Enchantment> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        List<EnchantSpec> pool = new ArrayList<>();
        for (Enchantment enchantment : registry.getTagValues(EnchantmentTagKeys.IN_ENCHANTING_TABLE)) {
            // Defensive rather than expected: a datapack is free to put either
            // into the tag, and neither belongs in a librarian's pool.
            if (enchantment.equals(Enchantment.MENDING) || CURSE_ENCHANTMENTS.contains(enchantment)) continue;
            pool.add(rolled(enchantment));
        }

        if (pool.isEmpty()) {
            // Only reachable if a datapack empties the tag. Better a wider pool
            // than a librarian with no book trade at all.
            plugin.getLogger().warning("#minecraft:in_enchanting_table is empty; librarian books fall back to every"
                    + " non-curse enchantment in the registry.");
            for (Enchantment enchantment : registry) {
                if (enchantment.equals(Enchantment.MENDING) || CURSE_ENCHANTMENTS.contains(enchantment)) continue;
                pool.add(rolled(enchantment));
            }
        }
        return List.copyOf(pool);
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof Villager villager)) return;
        if (villager.getProfession() != Villager.Profession.LIBRARIAN) return;

        int level = villager.getVillagerLevel();
        List<MerchantRecipe> recipes = new ArrayList<>(villager.getRecipes());

        // Strip any curse-enchant book before the per-level reconciliation
        // below runs, so it sees the resulting gap as "no book yet" and
        // fills it back in with a proper one - covers a villager that
        // already rolled a curse before this rule existed, or got one from
        // any other source.
        boolean changed = recipes.removeIf(this::isCurseBookTrade);
        changed |= reconcileNovice(recipes);

        if (level >= 2) changed |= reconcileApprentice(recipes);
        if (level >= 3) changed |= reconcileJourneyman(villager, recipes);
        if (level >= 4) changed |= reconcileExpert(villager, recipes);

        if (changed) {
            villager.setRecipes(recipes);
        }
    }

    // Vanilla's own librarian trade-unlock pipeline (the base data-driven
    // pool, or the trade_rebalance overlay on top of it if that's enabled)
    // can still try to hand a librarian a fresh enchanted-book trade at any
    // level. Every enchanted-book trade this listener wants a librarian to
    // have is built and inserted directly by the reconcile*() methods below
    // instead, so any book vanilla tries to add on its own is off-pool by
    // definition - cancel it outright rather than reconciling it after the
    // fact.
    @EventHandler
    public void onVillagerAcquireTrade(VillagerAcquireTradeEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) return;
        if (villager.getProfession() != Villager.Profession.LIBRARIAN) return;
        if (event.getRecipe().getResult().getType() == Material.ENCHANTED_BOOK) {
            event.setCancelled(true);
        }
    }

    private boolean reconcileNovice(List<MerchantRecipe> recipes) {
        boolean changed = false;
        if (recipes.stream().noneMatch(this::isPaperEmerald)) {
            recipes.add(createPaperEmerald());
            changed = true;
        }
        if (recipes.stream().noneMatch(this::isEmeraldBookshelf)) {
            recipes.add(createEmeraldBookshelf());
            changed = true;
        }
        return changed;
    }

    private boolean reconcileApprentice(List<MerchantRecipe> recipes) {
        boolean changed = false;
        if (recipes.stream().noneMatch(this::isBookEmerald)) {
            recipes.add(createBookEmerald());
            changed = true;
        }
        if (recipes.stream().noneMatch(this::isEmeraldLantern)) {
            recipes.add(createEmeraldLantern());
            changed = true;
        }
        return changed;
    }

    private boolean reconcileJourneyman(Villager villager, List<MerchantRecipe> recipes) {
        boolean changed = false;
        PersistentDataContainer pdc = villager.getPersistentDataContainer();

        String side = pdc.get(journeymanSideKey, PersistentDataType.STRING);
        if (side == null) {
            side = ThreadLocalRandom.current().nextBoolean() ? "ink_sac" : "glass";
            pdc.set(journeymanSideKey, PersistentDataType.STRING, side);
        }
        boolean wantInkSac = "ink_sac".equals(side);
        boolean hasInkSac = recipes.stream().anyMatch(this::isInkSacEmerald);
        boolean hasGlass = recipes.stream().anyMatch(this::isEmeraldGlass);

        if (wantInkSac) {
            if (!hasInkSac) { recipes.add(createInkSacEmerald()); changed = true; }
            if (hasGlass) { changed |= recipes.removeIf(this::isEmeraldGlass); }
        } else {
            if (!hasGlass) { recipes.add(createEmeraldGlass()); changed = true; }
            if (hasInkSac) { changed |= recipes.removeIf(this::isInkSacEmerald); }
        }

        if (recipes.stream().noneMatch(this::isEnchantedBookTrade)) {
            recipes.add(createJourneymanBook(villager));
            changed = true;
        }

        return changed;
    }

    private boolean reconcileExpert(Villager villager, List<MerchantRecipe> recipes) {
        boolean changed = false;
        PersistentDataContainer pdc = villager.getPersistentDataContainer();

        if (recipes.stream().noneMatch(this::isWritableBookEmerald)) {
            recipes.add(createWritableBookEmerald());
            changed = true;
        }
        // Expert doesn't add a book trade of its own, but the Journeyman
        // book from level 3 is left alone here - onVillagerAcquireTrade
        // already stops vanilla from adding a second, unwanted one at this
        // level, so there's nothing left to clean up.

        String side = pdc.get(expertSideKey, PersistentDataType.STRING);
        if (side == null) {
            side = ThreadLocalRandom.current().nextBoolean() ? "clock" : "compass";
            pdc.set(expertSideKey, PersistentDataType.STRING, side);
        }
        boolean wantClock = "clock".equals(side);
        boolean hasClock = recipes.stream().anyMatch(this::isEmeraldClock);
        boolean hasCompass = recipes.stream().anyMatch(this::isEmeraldCompass);

        if (wantClock) {
            if (!hasClock) { recipes.add(createEmeraldClock()); changed = true; }
            if (hasCompass) { changed |= recipes.removeIf(this::isEmeraldCompass); }
        } else {
            if (!hasCompass) { recipes.add(createEmeraldCompass()); changed = true; }
            if (hasClock) { changed |= recipes.removeIf(this::isEmeraldClock); }
        }

        return changed;
    }

    private boolean isPaperEmerald(MerchantRecipe r) {
        return r.getIngredients().stream().anyMatch(i -> i != null && i.getType() == Material.PAPER);
    }

    private boolean isEmeraldBookshelf(MerchantRecipe r) {
        return r.getResult().getType() == Material.BOOKSHELF;
    }

    private boolean isBookEmerald(MerchantRecipe r) {
        return r.getResult().getType() == Material.EMERALD
                && r.getIngredients().stream().anyMatch(i -> i != null && i.getType() == Material.BOOK);
    }

    private boolean isEmeraldLantern(MerchantRecipe r) {
        return r.getResult().getType() == Material.LANTERN;
    }

    private boolean isInkSacEmerald(MerchantRecipe r) {
        return r.getIngredients().stream().anyMatch(i -> i != null && i.getType() == Material.INK_SAC);
    }

    private boolean isEmeraldGlass(MerchantRecipe r) {
        return r.getResult().getType() == Material.GLASS;
    }

    private boolean isWritableBookEmerald(MerchantRecipe r) {
        return r.getIngredients().stream().anyMatch(i -> i != null && i.getType() == Material.WRITABLE_BOOK);
    }

    private boolean isEmeraldClock(MerchantRecipe r) {
        return r.getResult().getType() == Material.CLOCK;
    }

    private boolean isEmeraldCompass(MerchantRecipe r) {
        return r.getResult().getType() == Material.COMPASS;
    }

    private boolean isEnchantedBookTrade(MerchantRecipe r) {
        return r.getResult().getType() == Material.ENCHANTED_BOOK;
    }

    private boolean isCurseBookTrade(MerchantRecipe r) {
        if (!isEnchantedBookTrade(r)) return false;
        ItemMeta meta = r.getResult().getItemMeta();
        if (!(meta instanceof EnchantmentStorageMeta storage)) return false;
        return storage.getStoredEnchants().keySet().stream().anyMatch(CURSE_ENCHANTMENTS::contains);
    }

    // Novice: 24 Paper -> 1 Emerald, 16 uses, 2 villager xp.
    private MerchantRecipe createPaperEmerald() {
        MerchantRecipe recipe = new MerchantRecipe(new ItemStack(Material.EMERALD), 16);
        recipe.addIngredient(new ItemStack(Material.PAPER, 24));
        recipe.setVillagerExperience(2);
        return recipe;
    }

    // Novice: 9 Emerald -> 1 Bookshelf, 12 uses, 2 villager xp (vanilla
    // grants none for this trade - this listener's own deliberate override).
    private MerchantRecipe createEmeraldBookshelf() {
        MerchantRecipe recipe = new MerchantRecipe(new ItemStack(Material.BOOKSHELF), 12);
        recipe.addIngredient(new ItemStack(Material.EMERALD, 9));
        recipe.setVillagerExperience(2);
        return recipe;
    }

    // Apprentice: 4 Book -> 1 Emerald, 12 uses, 10 villager xp.
    private MerchantRecipe createBookEmerald() {
        MerchantRecipe recipe = new MerchantRecipe(new ItemStack(Material.EMERALD), 12);
        recipe.addIngredient(new ItemStack(Material.BOOK, 4));
        recipe.setVillagerExperience(10);
        return recipe;
    }

    // Apprentice: 1 Emerald -> 1 Lantern, 12 uses, 5 villager xp.
    private MerchantRecipe createEmeraldLantern() {
        MerchantRecipe recipe = new MerchantRecipe(new ItemStack(Material.LANTERN), 12);
        recipe.addIngredient(new ItemStack(Material.EMERALD, 1));
        recipe.setVillagerExperience(5);
        return recipe;
    }

    // Journeyman: 5 Ink Sac -> 1 Emerald, 12 uses, 20 villager xp.
    private MerchantRecipe createInkSacEmerald() {
        MerchantRecipe recipe = new MerchantRecipe(new ItemStack(Material.EMERALD), 12);
        recipe.addIngredient(new ItemStack(Material.INK_SAC, 5));
        recipe.setVillagerExperience(20);
        return recipe;
    }

    // Journeyman: 1 Emerald -> 4 Glass, 12 uses, 10 villager xp.
    private MerchantRecipe createEmeraldGlass() {
        MerchantRecipe recipe = new MerchantRecipe(new ItemStack(Material.GLASS, 4), 12);
        recipe.addIngredient(new ItemStack(Material.EMERALD, 1));
        recipe.setVillagerExperience(10);
        return recipe;
    }

    // Expert: 2 Writable Books -> 1 Emerald, 12 uses, 30 villager xp.
    private MerchantRecipe createWritableBookEmerald() {
        MerchantRecipe recipe = new MerchantRecipe(new ItemStack(Material.EMERALD), 12);
        recipe.addIngredient(new ItemStack(Material.WRITABLE_BOOK, 2));
        recipe.setVillagerExperience(30);
        return recipe;
    }

    // Expert: 5 Emerald -> 1 Clock, 12 uses, 15 villager xp.
    private MerchantRecipe createEmeraldClock() {
        MerchantRecipe recipe = new MerchantRecipe(new ItemStack(Material.CLOCK), 12);
        recipe.addIngredient(new ItemStack(Material.EMERALD, 5));
        recipe.setVillagerExperience(15);
        return recipe;
    }

    // Expert: 4 Emerald -> 1 Compass, 12 uses, 15 villager xp.
    private MerchantRecipe createEmeraldCompass() {
        MerchantRecipe recipe = new MerchantRecipe(new ItemStack(Material.COMPASS), 12);
        recipe.addIngredient(new ItemStack(Material.EMERALD, 4));
        recipe.setVillagerExperience(15);
        return recipe;
    }

    private MerchantRecipe createJourneymanBook(Villager villager) {
        boolean biomeSpecific = villager.getWorld().getFeatureFlags().contains(FeatureFlag.TRADE_REBALANCE);
        List<EnchantSpec> pool = biomeSpecific
                ? COMMON_ENCHANTS.getOrDefault(villager.getVillagerType(), genericPool)
                : genericPool;

        // Uniform pick across the whole pool - the former "master" entry (if
        // this biome has one and the pool in use is biome-specific) is just
        // one more equally-likely option here, not a separate rare roll.
        EnchantSpec picked = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));

        if (picked.level() != RANDOM_LEVEL) {
            // Matches vanilla's own Master-tier book xp.
            return buildBookRecipe(picked.enchantment(), picked.level(), fixedTierPrice(picked.level()), 30);
        }

        int level = ThreadLocalRandom.current().nextInt(1, Math.min(picked.enchantment().getMaxLevel(), 2) + 1);
        int price = ThreadLocalRandom.current().nextInt(5, 33);
        // Matches vanilla's own Journeyman-tier book xp.
        return buildBookRecipe(picked.enchantment(), level, price, 10);
    }

    // Vanilla's own trade_rebalance price formula for a biome's fixed
    // master-tier enchant.
    private int fixedTierPrice(int level) {
        int basePrice = level >= 3 ? 11 : 8;
        int range = level >= 3 ? 36 : 26;
        return basePrice + ThreadLocalRandom.current().nextInt(range);
    }

    private MerchantRecipe buildBookRecipe(Enchantment enchantment, int level, int price, int experience) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta storage) {
            storage.addStoredEnchant(enchantment, level, true);
            book.setItemMeta(storage);
        }

        MerchantRecipe recipe = new MerchantRecipe(book, 12);
        recipe.addIngredient(new ItemStack(Material.EMERALD, price));
        recipe.addIngredient(new ItemStack(Material.BOOK, 1));
        recipe.setVillagerExperience(experience);
        return recipe;
    }
}
