package com.example.vanillakfc.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.LootTables;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Vanilla's fishing treasure pool picks an enchanted book with 1-in-6 odds
 * (equal weight against name tag, saddle, bow, fishing rod, and nautilus
 * shell). This halves that to 1-in-12: on a book catch, a coin flip either
 * keeps it or re-rolls the treasure pool, rejecting further book results so
 * the freed-up probability lands back on the other five entries in their
 * original proportions instead of just being discarded.
 */
public final class FishingLootListener implements Listener {

    @EventHandler
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!(event.getCaught() instanceof Item caughtItem)) return;
        if (caughtItem.getItemStack().getType() != Material.ENCHANTED_BOOK) return;
        if (ThreadLocalRandom.current().nextBoolean()) return;

        Player player = event.getPlayer();
        LootTable treasure = LootTables.FISHING_TREASURE.getLootTable();
        LootContext context = new LootContext.Builder(caughtItem.getLocation())
                .killer(player)
                .build();

        ItemStack replacement;
        do {
            replacement = treasure.populateLoot(ThreadLocalRandom.current(), context)
                    .stream().findFirst().orElse(null);
        } while (replacement != null && replacement.getType() == Material.ENCHANTED_BOOK);

        if (replacement != null) {
            caughtItem.setItemStack(replacement);
        }
    }
}
