package com.example.vanillakfc.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Names a charged creeper as "Charged Creeper" in a player's death message,
 * instead of vanilla's plain "Creeper".
 *
 * <p>Vanilla puts the creeper in the message as its display name: the
 * translated "Creeper", with a hover naming that creeper's UUID. That piece
 * is swapped for "Charged Creeper", keeping the hover, wherever the UUID
 * belongs to a charged creeper. A creeper with a custom name keeps it.
 *
 * <p>Usually the creeper is the death's own cause and can be checked
 * directly. But a message can name it after the fact - "doomed to fall by
 * Creeper" after a blast knocked the player off a ledge - by which time it
 * has blown itself up and is gone. So charged creepers are also noted as
 * they go off and remembered for {@link #REMEMBER_TICKS}, well past how long
 * vanilla keeps an attacker in a player's combat history.
 */
public final class ChargedCreeperDeathMessageListener implements Listener {

    private static final String CREEPER_KEY = "entity.minecraft.creeper";
    private static final String CHARGED_NAME = "Charged Creeper";
    private static final int REMEMBER_TICKS = 20 * 60;

    // Charged creepers that went off, with the tick they did.
    private final Map<UUID, Integer> exploded = new HashMap<>();

    // Fired before the explosion does any damage, so the creeper is noted
    // before anyone it kills dies.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrime(ExplosionPrimeEvent event) {
        if (!(event.getEntity() instanceof Creeper creeper) || !creeper.isPowered()) return;
        int now = Bukkit.getCurrentTick();
        exploded.values().removeIf(tick -> now - tick > REMEMBER_TICKS);
        exploded.put(creeper.getUniqueId(), now);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Component message = event.deathMessage();
        if (message == null) return;

        Set<UUID> charged = new HashSet<>();
        int now = Bukkit.getCurrentTick();
        exploded.forEach((id, tick) -> {
            if (now - tick <= REMEMBER_TICKS) charged.add(id);
        });
        DamageSource source = event.getDamageSource();
        addIfCharged(source.getCausingEntity(), charged);
        addIfCharged(source.getDirectEntity(), charged);
        if (charged.isEmpty()) return;

        Component renamed = rename(message, charged);
        if (!renamed.equals(message)) {
            event.deathMessage(renamed);
        }
    }

    private static void addIfCharged(Entity entity, Set<UUID> charged) {
        if (entity instanceof Creeper creeper && creeper.isPowered()) {
            charged.add(creeper.getUniqueId());
        }
    }

    private static Component rename(Component component, Set<UUID> charged) {
        if (isChargedCreeperName(component, charged)) {
            return Component.text(CHARGED_NAME, component.style()).children(component.children());
        }
        if (component instanceof TranslatableComponent translatable) {
            List<ComponentLike> args = new ArrayList<>();
            for (TranslationArgument arg : translatable.arguments()) {
                args.add(arg.value() instanceof Component c ? rename(c, charged) : arg);
            }
            component = translatable.arguments(args);
        }
        List<Component> children = new ArrayList<>();
        for (Component child : component.children()) {
            children.add(rename(child, charged));
        }
        return component.children(children);
    }

    private static boolean isChargedCreeperName(Component component, Set<UUID> charged) {
        return component instanceof TranslatableComponent translatable
                && translatable.key().equals(CREEPER_KEY)
                && component.hoverEvent() != null
                && component.hoverEvent().value() instanceof HoverEvent.ShowEntity entity
                && charged.contains(entity.id());
    }
}
