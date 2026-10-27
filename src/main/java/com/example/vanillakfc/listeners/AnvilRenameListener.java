package com.example.vanillakfc.listeners;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;

import java.util.Objects;
import java.util.Set;

/**
 * Makes renaming at an anvil free.
 *
 * <p>Vanilla charges a rename on its own the item's prior-work penalty plus
 * one level (never "Too Expensive!" - it caps that at 39), and a rename done
 * alongside a repair or enchant adds one level to that operation. Here a
 * rename on its own costs nothing, and one done alongside something else no
 * longer adds its level - the rest of the operation is charged as before.
 *
 * <p>The penalty itself is left alone, as vanilla already does for a rename:
 * it only grows when the anvil does something besides renaming, and then by
 * the same amount with or without a new name. A rename on its own keeps the
 * input's penalty here even where vanilla would have taken the second slot's
 * (a rename with an item in the second slot that adds nothing).
 *
 * <p>Runs early, on vanilla's pricing. {@link AnvilRepairListener} runs after
 * and sets its repairs' cost outright, with no rename level in it, so a
 * level taken off here never comes off twice.
 */
public final class AnvilRenameListener implements Listener {

    private static final int BASE_SLOT = 0;

    private static final Set<DataComponentType> NAME_AND_PENALTY =
            Set.of(DataComponentTypes.CUSTOM_NAME, DataComponentTypes.REPAIR_COST);

    @EventHandler(priority = EventPriority.LOW)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        ItemStack base = event.getInventory().getItem(BASE_SLOT);
        if (result == null || result.isEmpty() || base == null || base.isEmpty()) return;

        // Read off the result rather than the rename box: vanilla only renames
        // when the typed name differs from what the item already shows, and a
        // listener that built its own result may have dropped the name.
        boolean renamed = !Objects.equals(
                base.getData(DataComponentTypes.CUSTOM_NAME), result.getData(DataComponentTypes.CUSTOM_NAME));
        if (!renamed) return;

        AnvilView view = event.getView();
        if (result.matchesWithoutData(base, NAME_AND_PENALTY)) {
            // Paper lets a 0-level result be taken; vanilla would refuse it.
            result.setData(DataComponentTypes.REPAIR_COST, base.getDataOrDefault(DataComponentTypes.REPAIR_COST, 0));
            event.setResult(result);
            view.setRepairCost(0);
        } else if (view.getRepairCost() > 1) {
            view.setRepairCost(view.getRepairCost() - 1);
        }
    }
}
