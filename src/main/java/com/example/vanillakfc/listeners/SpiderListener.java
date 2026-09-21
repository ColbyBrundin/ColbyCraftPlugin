package com.example.vanillakfc.listeners;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Spider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Spiders move faster than vanilla. Uses {@link EntityType#SPIDER} rather
 * than {@code instanceof Spider} so cave spiders - a distinct entity type
 * that happens to implement the same Bukkit interface - are excluded.
 */
public final class SpiderListener implements Listener {

    private static final double MOVEMENT_SPEED_MULTIPLIER = 1.5;

    @EventHandler
    public void onSpiderSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.SPIDER) return;
        Spider spider = (Spider) event.getEntity();
        AttributeInstance speed = spider.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(speed.getBaseValue() * MOVEMENT_SPEED_MULTIPLIER);
        }
    }
}
