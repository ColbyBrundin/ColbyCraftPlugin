package com.example.vanillakfc.listeners;

import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

public final class IronGolemListener implements Listener {

    @EventHandler
    public void onIronGolemDeath(EntityDeathEvent event) {
        if (event.getEntityType() == EntityType.IRON_GOLEM) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }
}
