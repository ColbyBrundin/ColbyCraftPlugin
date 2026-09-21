package com.example.vanillakfc.recipes;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Reworks iron/gold/diamond gear recipes to trade ingots for blocks of the
 * same material, and reworks the netherite block + upgrade recipes. The
 * enchanting table gets the same treatment, trading both of its diamonds
 * for diamond blocks.
 *
 * <p>Every valid arrangement of which slot(s) hold the block is registered
 * as its own recipe, so the block can go in any of the original ingot
 * slots (not just one fixed position). Only the first arrangement is shown
 * in the recipe book though - see {@link #getHiddenAlternates()} - so the
 * flexibility doesn't cost one book entry per position.
 *
 * <p>Also doubles the chain recipe's output, adds chainmail armor recipes
 * (chainmail has none in vanilla) using the same shapes as the other armor
 * tiers with chains instead of ingots/gems/blocks, and reworks all four
 * leather armor recipes to use string and wood planks alongside leather,
 * with rotten flesh accepted as a 1-for-1 substitute anywhere the shape
 * calls for leather.
 *
 * <p>Every rebuilt recipe is tagged with its {@link CraftingBookCategory}
 * explicitly - a freshly constructed {@link ShapedRecipe} always defaults to
 * Misc, and since these fully replace the vanilla recipe, leaving that
 * default in place would silently move them out of whatever tab they
 * belonged in. Gear is {@code EQUIPMENT}; the enchanting table is
 * {@code MISC}, which is what vanilla's own category-less recipe JSON
 * resolves to.
 */
public final class CustomRecipes {

    public static final Map<Material, Material> INGOT_TO_BLOCK = new EnumMap<>(Material.class);
    static {
        INGOT_TO_BLOCK.put(Material.IRON_INGOT, Material.IRON_BLOCK);
        INGOT_TO_BLOCK.put(Material.GOLD_INGOT, Material.GOLD_BLOCK);
        INGOT_TO_BLOCK.put(Material.DIAMOND, Material.DIAMOND_BLOCK);
    }

    private static final Set<Material> TOOLS_AND_WEAPONS = EnumSet.of(
            Material.IRON_PICKAXE, Material.IRON_AXE, Material.IRON_HOE, Material.IRON_SHOVEL, Material.IRON_SWORD, Material.IRON_SPEAR,
            Material.GOLDEN_PICKAXE, Material.GOLDEN_AXE, Material.GOLDEN_HOE, Material.GOLDEN_SHOVEL, Material.GOLDEN_SWORD, Material.GOLDEN_SPEAR,
            Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE, Material.DIAMOND_HOE, Material.DIAMOND_SHOVEL, Material.DIAMOND_SWORD, Material.DIAMOND_SPEAR
    );

    private static final Set<Material> ARMOR = EnumSet.of(
            Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
            Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS,
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS
    );

    private static final char[] FREE_CHAR_CANDIDATES = "BVKQZJUY".toCharArray();

    private static final int CHAIN_OUTPUT_COUNT = 2;

    private static final Map<Material, String[]> CHAINMAIL_SHAPES = new EnumMap<>(Material.class);
    static {
        CHAINMAIL_SHAPES.put(Material.CHAINMAIL_HELMET, new String[]{"XXX", "X X"});
        CHAINMAIL_SHAPES.put(Material.CHAINMAIL_CHESTPLATE, new String[]{"X X", "XXX", "XXX"});
        CHAINMAIL_SHAPES.put(Material.CHAINMAIL_LEGGINGS, new String[]{"XXX", "X X", "X X"});
        CHAINMAIL_SHAPES.put(Material.CHAINMAIL_BOOTS, new String[]{"X X", "X X"});
    }

    // 'L' = leather (or rotten flesh), 'S' = string, 'P' = any wood planks.
    private static final Map<Material, String[]> LEATHER_ARMOR_SHAPES = new EnumMap<>(Material.class);
    static {
        LEATHER_ARMOR_SHAPES.put(Material.LEATHER_HELMET, new String[]{"LLL", "S S"});
        LEATHER_ARMOR_SHAPES.put(Material.LEATHER_CHESTPLATE, new String[]{"S S", "LPL", "LPL"});
        LEATHER_ARMOR_SHAPES.put(Material.LEATHER_LEGGINGS, new String[]{"LPL", "S S", "L L"});
        LEATHER_ARMOR_SHAPES.put(Material.LEATHER_BOOTS, new String[]{"S S", "L L"});
    }

    /**
     * Every recipe registered here, mapped to the distinct ingredient choices
     * a player needs to hold before it should show up in their recipe book.
     *
     * <p>Recipes registered under this plugin's own namespace (the chainmail
     * set and every {@code _block_variant_} arrangement) have no vanilla
     * "recipe unlocked" advancement behind them, so the client never
     * discovers them on its own - without an explicit
     * {@link org.bukkit.entity.HumanEntity#discoverRecipes} they stay
     * invisible until the player happens to craft one by hand. The
     * vanilla-keyed replacements are tracked too, because their reworked
     * ingredients no longer match what the vanilla advancement watches for
     * (leather armor now accepts rotten flesh; the netherite upgrades now
     * take a block instead of an ingot), so those advancements can miss
     * players who only ever obtain the substitute.
     */
    private static final Map<NamespacedKey, List<RecipeChoice>> REGISTERED = new LinkedHashMap<>();

    /**
     * Block-position arrangements that stay craftable but are kept out of the
     * recipe book.
     *
     * <p>Letting a player put the block in any of the original ingot slots
     * needs one registered recipe per position, and each of those would
     * otherwise claim its own recipe-book entry - three near-identical
     * pickaxe entries differing only in which cell holds the block. Minecraft
     * renders one fixed shape per recipe and offers no way to cycle a single
     * entry through several, so the book shows the first arrangement only.
     * The others still craft exactly as before; they are simply never
     * revealed, which keeps the flexibility without the clutter.
     */
    private static final Set<NamespacedKey> HIDDEN_ALTERNATES = new HashSet<>();

    private CustomRecipes() {
    }

    public static Map<NamespacedKey, List<RecipeChoice>> getRegisteredRecipes() {
        return Collections.unmodifiableMap(REGISTERED);
    }

    public static Set<NamespacedKey> getHiddenAlternates() {
        return Collections.unmodifiableSet(HIDDEN_ALTERNATES);
    }

    // Adds the recipe to the server and records what it takes to craft, so
    // RecipeDiscoveryListener can unlock it once a player holds the parts.
    private static void register(Recipe recipe) {
        Bukkit.addRecipe(recipe);
        REGISTERED.put(keyOf(recipe), distinctChoices(recipe));
    }

    private static NamespacedKey keyOf(Recipe recipe) {
        return ((org.bukkit.Keyed) recipe).getKey();
    }

    private static List<RecipeChoice> distinctChoices(Recipe recipe) {
        List<RecipeChoice> choices = new ArrayList<>();
        if (recipe instanceof ShapedRecipe shaped) {
            for (RecipeChoice choice : shaped.getChoiceMap().values()) {
                addIfAbsent(choices, choice);
            }
        } else if (recipe instanceof SmithingTransformRecipe smithing) {
            addIfAbsent(choices, smithing.getTemplate());
            addIfAbsent(choices, smithing.getBase());
            addIfAbsent(choices, smithing.getAddition());
        }
        return choices;
    }

    private static void addIfAbsent(List<RecipeChoice> choices, RecipeChoice choice) {
        if (choice != null && !choices.contains(choice)) {
            choices.add(choice);
        }
    }

    public static void registerAll(Plugin plugin, Logger logger) {
        REGISTERED.clear();
        HIDDEN_ALTERNATES.clear();
        for (Material tool : TOOLS_AND_WEAPONS) {
            replaceIngotsWithBlock(plugin, tool, 1, CraftingBookCategory.EQUIPMENT, logger);
        }
        for (Material piece : ARMOR) {
            replaceIngotsWithBlock(plugin, piece, 2, CraftingBookCategory.EQUIPMENT, logger);
        }
        replaceIngotsWithBlock(plugin, Material.SHIELD, 1, CraftingBookCategory.EQUIPMENT, logger);
        // Both diamonds, not one: the vanilla shape (" B " / "D#D" / "###")
        // has exactly two diamond slots, so asking for two replacements
        // yields a single variant with both swapped rather than a pair of
        // half-converted alternatives. Vanilla's own recipe JSON declares no
        // category, which means Misc - it is not Equipment like the callers
        // above.
        replaceIngotsWithBlock(plugin, Material.ENCHANTING_TABLE, 2, CraftingBookCategory.MISC, logger);
        rebuildNetheriteBlockRecipe();
        switchNetheriteUpgradesToBlock();
        doubleChainRecipeOutput(logger);
        registerChainmailRecipes(plugin);
        registerLeatherArmorRecipes();

        long pluginNamespaced = REGISTERED.keySet().stream()
                .filter(key -> key.getNamespace().equals(plugin.getName().toLowerCase()))
                .count();
        logger.info("Registered " + REGISTERED.size() + " recipes (" + pluginNamespaced
                + " under this plugin's namespace, which need explicit recipe-book unlocking; "
                + HIDDEN_ALTERNATES.size() + " of those are alternate block positions kept out of the book, "
                + "leaving " + (REGISTERED.size() - HIDDEN_ALTERNATES.size()) + " book entries).");
    }

    // Keeps the vanilla shape/ingredients (whatever they are) and only
    // bumps the result count, rather than assuming/hardcoding the shape.
    private static void doubleChainRecipeOutput(Logger logger) {
        ShapedRecipe existing = findShapedRecipeFor(Material.IRON_CHAIN);
        if (existing == null) {
            logger.warning("No shaped recipe found for CHAIN; leaving it untouched.");
            return;
        }

        Map<Character, RecipeChoice> choices = existing.getChoiceMap();
        String[] shape = normalizeShape(existing.getShape(), choices);
        NamespacedKey key = existing.getKey();

        Bukkit.removeRecipe(key);
        ShapedRecipe doubled = new ShapedRecipe(key, new ItemStack(Material.IRON_CHAIN, CHAIN_OUTPUT_COUNT));
        doubled.shape(shape);
        for (String row : shape) {
            for (char c : row.toCharArray()) {
                if (c == ' ') continue;
                RecipeChoice choice = choices.get(c);
                if (choice != null) {
                    doubled.setIngredient(c, choice);
                }
            }
        }
        register(doubled);
    }

    // Chainmail armor has no vanilla crafting recipe at all; these mirror
    // the other armor tiers' exact shapes with chains filling every
    // ingredient slot instead of ingots/gems/blocks.
    private static void registerChainmailRecipes(Plugin plugin) {
        for (Map.Entry<Material, String[]> entry : CHAINMAIL_SHAPES.entrySet()) {
            Material result = entry.getKey();
            NamespacedKey key = new NamespacedKey(plugin, result.getKey().getKey());
            ShapedRecipe recipe = new ShapedRecipe(key, new ItemStack(result));
            recipe.shape(entry.getValue());
            recipe.setIngredient('X', Material.IRON_CHAIN);
            recipe.setCategory(CraftingBookCategory.EQUIPMENT);
            register(recipe);
        }
    }

    // Replaces (not adds alongside) each vanilla leather armor recipe under
    // its original vanilla key, so the old all-leather recipe is fully gone
    // rather than left as an alternate way to craft the same item.
    private static void registerLeatherArmorRecipes() {
        RecipeChoice leatherOrRottenFlesh = new RecipeChoice.MaterialChoice(Material.LEATHER, Material.ROTTEN_FLESH);
        RecipeChoice string = new RecipeChoice.MaterialChoice(Material.STRING);
        RecipeChoice anyPlanks = new RecipeChoice.MaterialChoice(Tag.ITEMS_PLANKS);

        for (Map.Entry<Material, String[]> entry : LEATHER_ARMOR_SHAPES.entrySet()) {
            Material result = entry.getKey();
            String[] shape = entry.getValue();
            NamespacedKey key = NamespacedKey.minecraft(result.getKey().getKey());

            Bukkit.removeRecipe(key);
            ShapedRecipe recipe = new ShapedRecipe(key, new ItemStack(result));
            recipe.shape(shape);
            recipe.setIngredient('L', leatherOrRottenFlesh);
            recipe.setIngredient('S', string);
            if (String.join("", shape).indexOf('P') >= 0) {
                recipe.setIngredient('P', anyPlanks);
            }
            recipe.setCategory(CraftingBookCategory.EQUIPMENT);
            register(recipe);
        }
    }

    private static void replaceIngotsWithBlock(Plugin plugin, Material result, int replaceCount,
                                               CraftingBookCategory category, Logger logger) {
        ShapedRecipe shaped = findShapedRecipeFor(result);
        if (shaped == null) {
            logger.warning("No shaped recipe found for " + result + "; leaving it untouched.");
            return;
        }

        Map<Character, RecipeChoice> originalChoices = shaped.getChoiceMap();
        // Paper assigns every grid cell its own symbol, including blank ones
        // (which map to a null choice instead of a literal space). Normalize
        // those back to spaces so the rest of this method can treat them as
        // the empty cells they actually are.
        String[] originalShape = normalizeShape(shaped.getShape(), originalChoices);

        Material ingot = findReplaceableIngredient(shaped);
        if (ingot == null) {
            logger.warning("No iron/gold/diamond ingredient found in " + result + " recipe (shape="
                    + String.join("|", originalShape) + ", choices=" + originalChoices + "); leaving it untouched.");
            return;
        }
        Material block = INGOT_TO_BLOCK.get(ingot);

        List<int[]> ingotPositions = new ArrayList<>();
        for (int row = 0; row < originalShape.length; row++) {
            String line = originalShape[row];
            for (int col = 0; col < line.length(); col++) {
                char c = line.charAt(col);
                if (c == ' ') {
                    continue;
                }
                RecipeChoice choice = originalChoices.get(c);
                if (choice != null && choice.test(new ItemStack(ingot))) {
                    ingotPositions.add(new int[]{row, col});
                }
            }
        }

        if (ingotPositions.size() < replaceCount) {
            logger.warning("Recipe for " + result + " only has " + ingotPositions.size() + " ingot slot(s), need "
                    + replaceCount + "; leaving it untouched.");
            return;
        }

        char blockChar = pickFreeChar(originalShape);
        List<List<int[]>> combos = new ArrayList<>();
        combinations(ingotPositions, replaceCount, 0, new ArrayList<>(), combos);

        NamespacedKey originalKey = shaped.getKey();
        Bukkit.removeRecipe(originalKey);

        int variantIndex = 0;
        for (List<int[]> combo : combos) {
            char[][] grid = new char[originalShape.length][];
            for (int row = 0; row < originalShape.length; row++) {
                grid[row] = originalShape[row].toCharArray();
            }
            for (int[] pos : combo) {
                grid[pos[0]][pos[1]] = blockChar;
            }
            String[] variantShape = new String[grid.length];
            for (int row = 0; row < grid.length; row++) {
                variantShape[row] = new String(grid[row]);
            }

            Set<Character> usedChars = new HashSet<>();
            for (String row : variantShape) {
                for (char c : row.toCharArray()) {
                    if (c != ' ') {
                        usedChars.add(c);
                    }
                }
            }

            Map<Character, RecipeChoice> ingredients = new HashMap<>();
            boolean ok = true;
            for (char c : usedChars) {
                if (c == blockChar) {
                    ingredients.put(c, new RecipeChoice.MaterialChoice(block));
                    continue;
                }
                RecipeChoice choice = originalChoices.get(c);
                if (choice == null) {
                    logger.warning("Skipping a block-position variant for " + result + ": symbol '" + c
                            + "' has no ingredient in the original recipe.");
                    ok = false;
                    break;
                }
                ingredients.put(c, choice);
            }
            if (!ok) {
                continue;
            }

            // The first arrangement built is the one the recipe book will
            // show; the rest stay craftable but hidden, so a tool doesn't
            // occupy one book entry per block position.
            boolean canonical = variantIndex == 0;
            NamespacedKey variantKey = new NamespacedKey(plugin, originalKey.getKey() + "_block_variant_" + variantIndex);
            variantIndex++;

            ShapedRecipe variant = new ShapedRecipe(variantKey, shaped.getResult());
            variant.shape(variantShape);
            for (Map.Entry<Character, RecipeChoice> entry : ingredients.entrySet()) {
                variant.setIngredient(entry.getKey(), entry.getValue());
            }
            // A fresh ShapedRecipe always defaults to Misc, which would lose
            // whatever tab the vanilla recipe being replaced actually sat in,
            // so the caller states the correct one explicitly.
            variant.setCategory(category);
            register(variant);
            if (!canonical) {
                HIDDEN_ALTERNATES.add(variantKey);
            }
        }
    }

    private static void combinations(List<int[]> items, int k, int start, List<int[]> current, List<List<int[]>> out) {
        if (current.size() == k) {
            out.add(new ArrayList<>(current));
            return;
        }
        for (int i = start; i < items.size(); i++) {
            current.add(items.get(i));
            combinations(items, k, i + 1, current, out);
            current.remove(current.size() - 1);
        }
    }

    private static String[] normalizeShape(String[] rawShape, Map<Character, RecipeChoice> choices) {
        String[] normalized = new String[rawShape.length];
        for (int row = 0; row < rawShape.length; row++) {
            char[] chars = rawShape[row].toCharArray();
            for (int col = 0; col < chars.length; col++) {
                char c = chars[col];
                if (c != ' ' && choices.get(c) == null) {
                    chars[col] = ' ';
                }
            }
            normalized[row] = new String(chars);
        }
        return normalized;
    }

    private static ShapedRecipe findShapedRecipeFor(Material result) {
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe = it.next();
            if (recipe instanceof ShapedRecipe shaped && shaped.getResult().getType() == result) {
                return shaped;
            }
        }
        return null;
    }

    private static Material findReplaceableIngredient(ShapedRecipe shaped) {
        for (Material ingot : INGOT_TO_BLOCK.keySet()) {
            ItemStack probe = new ItemStack(ingot);
            for (RecipeChoice choice : shaped.getChoiceMap().values()) {
                if (choice != null && choice.test(probe)) {
                    return ingot;
                }
            }
        }
        return null;
    }

    private static char pickFreeChar(String[] shape) {
        Set<Character> used = new HashSet<>();
        for (String row : shape) {
            for (char c : row.toCharArray()) {
                used.add(c);
            }
        }
        for (char candidate : FREE_CHAR_CANDIDATES) {
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No free recipe symbol available for shape " + String.join("|", shape));
    }

    private static void rebuildNetheriteBlockRecipe() {
        NamespacedKey key = NamespacedKey.minecraft("netherite_block");
        Bukkit.removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, new ItemStack(Material.NETHERITE_BLOCK));
        recipe.shape("II ", "II ", "   ");
        recipe.setIngredient('I', Material.NETHERITE_INGOT);
        register(recipe);
    }

    private static void switchNetheriteUpgradesToBlock() {
        ItemStack netheriteIngotProbe = new ItemStack(Material.NETHERITE_INGOT);
        RecipeChoice blockAddition = new RecipeChoice.MaterialChoice(Material.NETHERITE_BLOCK);

        List<SmithingTransformRecipe> toReplace = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe = it.next();
            if (recipe instanceof SmithingTransformRecipe str && str.getAddition().test(netheriteIngotProbe)) {
                toReplace.add(str);
            }
        }

        for (SmithingTransformRecipe str : toReplace) {
            NamespacedKey key = str.getKey();
            Bukkit.removeRecipe(key);
            SmithingTransformRecipe replacement = new SmithingTransformRecipe(
                    key, str.getResult(), str.getTemplate(), str.getBase(), blockAddition
            );
            register(replacement);
        }
    }
}
