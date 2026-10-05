package com.example.vanillakfc.listeners;

import com.example.vanillakfc.advancements.Advancements;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

/**
 * Awards the "Fallen Angel" advancement for killing a charged creeper.
 *
 * <p>Credit goes to whoever vanilla credits with the kill
 * ({@link Creeper#getKiller()}), so arrows, tridents and the like count, and a
 * creeper that blows itself up counts for nobody.
 */
public final class ChargedCreeperKillListener implements Listener {

    private static final String ADVANCEMENT = "fallen_angel";

    private final Advancements advancements;

    public ChargedCreeperKillListener(Advancements advancements) {
        this.advancements = advancements;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper) || !creeper.isPowered()) return;
        Player killer = creeper.getKiller();
        if (killer == null) return;
        advancements.award(killer, ADVANCEMENT);
    }
}
