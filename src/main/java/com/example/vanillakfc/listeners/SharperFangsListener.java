package com.example.vanillakfc.listeners;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Enchantable;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EnchantingInventory;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * The plugin side of turning Fire Protection into Sharper Fangs.
 *
 * <p>The enchantment itself is redefined in the bundled datapack: it keeps
 * its id ({@code minecraft:fire_protection}), so every existing book simply
 * reads "Sharper Fangs" with nothing to re-id, but it now goes only on wolf
 * armor, has a single level, and gives the wolf wearing it +4 attack damage.
 * Thorns and Unbreaking are opened to wolf armor there too. The things a
 * datapack can't do are handled here.
 *
 * <p><b>Enchantable wolf armor.</b> An enchanting table only accepts items
 * carrying the {@code enchantable} component, which vanilla wolf armor
 * doesn't have and which no datapack can add to an item's defaults. So each
 * wolf armor stack is given it individually, at iron armor's enchantability
 * (9), so table rolls come out as they would on iron armor: when crafted,
 * during the conversion pass below, and - as a catch-all for any piece that
 * slipped through - when it's put into an enchanting table.
 *
 * <p><b>Armor that already has Fire Protection.</b> It can no longer go on
 * armor, so existing armor pieces carrying it have it swapped for
 * Protection at the same level (or the level they already had, if higher).
 *
 * <p><b>Levels above I.</b> It used to go to IV, as Wolf Protection, and the
 * game doesn't lower a level above an enchantment's maximum on its own - a
 * Wolf Protection III book would read "Sharper Fangs III". The bonus is the
 * same at any level, so this is only so it reads right: books and wolf armor
 * carrying a higher level are set to I.
 *
 * <p>Both are a one-time pass over everything in the world, done as it
 * loads: containers and entities when their chunk loads (each chunk is marked once
 * done), players' inventories and ender chests on join (each player marked
 * once done), and everything already loaded at startup. Shulker boxes and
 * bundles are searched inside, as are villagers' trade offers. The markers
 * are new for Sharper Fangs, so everything already passed over for Wolf
 * Protection is gone over once more for the levels.
 */
public final class SharperFangsListener implements Listener {

    /** Iron armor's enchantability. */
    private static final int WOLF_ARMOR_ENCHANTABILITY = 9;

    private final Plugin plugin;
    private final NamespacedKey blocksDoneKey;
    private final NamespacedKey entitiesDoneKey;
    private final NamespacedKey playerDoneKey;

    public SharperFangsListener(Plugin plugin) {
        this.plugin = plugin;
        this.blocksDoneKey = new NamespacedKey(plugin, "sharper_fangs_blocks_done");
        this.entitiesDoneKey = new NamespacedKey(plugin, "sharper_fangs_entities_done");
        this.playerDoneKey = new NamespacedKey(plugin, "sharper_fangs_done");
    }

    /** The pass over everything already loaded when the plugin starts. */
    public void convertLoaded() {
        int changed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                changed += convertBlocks(chunk);
                // A chunk can be loaded before its entities are. Marking it
                // done now would skip them for good, so it's left to
                // EntitiesLoadEvent, which fires once they arrive.
                if (chunk.isEntitiesLoaded()) {
                    changed += convertEntities(chunk, List.of(chunk.getEntities()));
                }
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            changed += convertPlayer(player);
        }
        logIfChanged(changed, "already-loaded chunks and online players");
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        logIfChanged(convertBlocks(event.getChunk()), "chunk " + describe(event.getChunk()));
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        logIfChanged(convertEntities(event.getChunk(), event.getEntities()), "entities in chunk " + describe(event.getChunk()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        logIfChanged(convertPlayer(event.getPlayer()), "player " + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result != null && makeEnchantable(result)) {
            event.getInventory().setResult(result);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        ItemStack result = event.getResult();
        if (makeEnchantable(result)) {
            event.setResult(result);
        }
    }

    // Catch-all for a wolf armor piece the other paths missed (e.g. from a
    // command). Checked a tick later, once the click or drag has actually put
    // the item in the table - setting it back then makes the table re-roll
    // its offers for the now-enchantable item.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchantingClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory() instanceof EnchantingInventory table) {
            checkTableNextTick(table);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchantingDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory() instanceof EnchantingInventory table) {
            checkTableNextTick(table);
        }
    }

    private void checkTableNextTick(EnchantingInventory table) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack item = table.getItem();
            if (item != null && makeEnchantable(item)) {
                table.setItem(item);
            }
        });
    }

    private int convertBlocks(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        if (pdc.has(blocksDoneKey, PersistentDataType.BYTE)) return 0;
        int changed = 0;
        for (BlockState state : chunk.getTileEntities()) {
            // getInventory() on a placed block's state is the live inventory,
            // not a snapshot, so setting items there changes the block.
            if (state instanceof InventoryHolder holder) {
                changed += convertInventory(holder.getInventory());
            }
        }
        pdc.set(blocksDoneKey, PersistentDataType.BYTE, (byte) 1);
        return changed;
    }

    private int convertEntities(Chunk chunk, List<Entity> entities) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        if (pdc.has(entitiesDoneKey, PersistentDataType.BYTE)) return 0;
        int changed = 0;
        for (Entity entity : entities) {
            changed += convertEntity(entity);
        }
        pdc.set(entitiesDoneKey, PersistentDataType.BYTE, (byte) 1);
        return changed;
    }

    private int convertEntity(Entity entity) {
        int changed = 0;
        if (entity instanceof Item item) {
            ItemStack stack = item.getItemStack();
            if (convert(stack)) {
                item.setItemStack(stack);
                changed++;
            }
        } else if (entity instanceof ItemFrame frame) {
            ItemStack stack = frame.getItem();
            if (convert(stack)) {
                frame.setItem(stack, false);
                changed++;
            }
        }
        // Players are covered on join, with their ender chest too.
        if (entity instanceof LivingEntity living && !(entity instanceof Player)) {
            EntityEquipment equipment = living.getEquipment();
            if (equipment != null) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    if (!living.canUseEquipmentSlot(slot)) continue;
                    ItemStack stack = equipment.getItem(slot);
                    if (convert(stack)) {
                        equipment.setItem(slot, stack, true);
                        changed++;
                    }
                }
            }
        }
        // Minecart chests, chest boats, donkeys and llamas, villagers' own
        // inventories, and so on.
        if (entity instanceof InventoryHolder holder && !(entity instanceof Player)) {
            changed += convertInventory(holder.getInventory());
        }
        if (entity instanceof AbstractVillager villager) {
            changed += convertTrades(villager);
        }
        return changed;
    }

    private int convertPlayer(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (pdc.has(playerDoneKey, PersistentDataType.BYTE)) return 0;
        int changed = convertInventory(player.getInventory()) + convertInventory(player.getEnderChest());
        pdc.set(playerDoneKey, PersistentDataType.BYTE, (byte) 1);
        return changed;
    }

    private int convertInventory(Inventory inventory) {
        int changed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack != null && convert(stack)) {
                inventory.setItem(i, stack);
                changed++;
            }
        }
        return changed;
    }

    // An armorer's existing offers can include armor enchanted with Fire
    // Protection. The trade is rebuilt from its getters, not with
    // MerchantRecipe's copy constructor - that one reads fields the server's
    // own trades leave at 0 (see DiamondTradePriceListener).
    private int convertTrades(AbstractVillager villager) {
        int changed = 0;
        List<MerchantRecipe> recipes = villager.getRecipes();
        for (int i = 0; i < recipes.size(); i++) {
            MerchantRecipe recipe = recipes.get(i);
            ItemStack result = recipe.getResult();
            if (!convert(result)) continue;
            MerchantRecipe copy = new MerchantRecipe(result, recipe.getUses(), recipe.getMaxUses(),
                    recipe.hasExperienceReward(), recipe.getVillagerExperience(), recipe.getPriceMultiplier(),
                    recipe.getDemand(), recipe.getSpecialPrice(), recipe.shouldIgnoreDiscounts());
            copy.setIngredients(recipe.getIngredients());
            villager.setRecipe(i, copy);
            changed++;
        }
        return changed;
    }

    /**
     * Brings one stack up to date in place, including anything inside it.
     *
     * @return true if the stack was changed
     */
    private boolean convert(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        boolean changed = makeEnchantable(stack);
        changed |= replaceFireProtection(stack);
        changed |= capSharperFangs(stack);
        changed |= convertContents(stack);
        return changed;
    }

    private static boolean makeEnchantable(ItemStack stack) {
        if (stack.getType() != Material.WOLF_ARMOR || stack.hasData(DataComponentTypes.ENCHANTABLE)) return false;
        stack.setData(DataComponentTypes.ENCHANTABLE, Enchantable.enchantable(WOLF_ARMOR_ENCHANTABILITY));
        return true;
    }

    // Books keep theirs - on a book it is now simply Sharper Fangs. Wolf
    // armor is the one item it still belongs on.
    private static boolean replaceFireProtection(ItemStack stack) {
        if (stack.getType() == Material.ENCHANTED_BOOK || stack.getType() == Material.WOLF_ARMOR) return false;
        int level = stack.getEnchantmentLevel(Enchantment.FIRE_PROTECTION);
        if (level <= 0) return false;
        stack.removeEnchantment(Enchantment.FIRE_PROTECTION);
        // Fire Protection and Protection were mutually exclusive, so this
        // only comes up for an item given both by a command - in which case
        // the higher of the two levels is kept.
        int protection = Math.max(level, stack.getEnchantmentLevel(Enchantment.PROTECTION));
        stack.addUnsafeEnchantment(Enchantment.PROTECTION, protection);
        return true;
    }

    private static boolean capSharperFangs(ItemStack stack) {
        if (stack.getType() == Material.WOLF_ARMOR) {
            if (stack.getEnchantmentLevel(Enchantment.FIRE_PROTECTION) <= 1) return false;
            stack.addUnsafeEnchantment(Enchantment.FIRE_PROTECTION, 1);
            return true;
        }
        if (stack.getType() == Material.ENCHANTED_BOOK && stack.getItemMeta() instanceof EnchantmentStorageMeta meta
                && meta.getStoredEnchantLevel(Enchantment.FIRE_PROTECTION) > 1) {
            meta.addStoredEnchant(Enchantment.FIRE_PROTECTION, 1, true);
            stack.setItemMeta(meta);
            return true;
        }
        return false;
    }

    private boolean convertContents(ItemStack stack) {
        if (!stack.hasItemMeta()) return false;
        if (stack.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox box) {
            if (convertInventory(box.getInventory()) == 0) return false;
            meta.setBlockState(box);
            stack.setItemMeta(meta);
            return true;
        }
        if (stack.getItemMeta() instanceof BundleMeta meta && meta.hasItems()) {
            boolean changed = false;
            List<ItemStack> items = new ArrayList<>(meta.getItems());
            for (ItemStack item : items) {
                changed |= convert(item);
            }
            if (!changed) return false;
            meta.setItems(items);
            stack.setItemMeta(meta);
            return true;
        }
        return false;
    }

    private void logIfChanged(int changed, String where) {
        if (changed > 0) {
            plugin.getLogger().info("Sharper Fangs conversion: updated " + changed + " item(s) in " + where + ".");
        }
    }

    private static String describe(Chunk chunk) {
        return chunk.getWorld().getName() + " " + chunk.getX() + "," + chunk.getZ();
    }
}
