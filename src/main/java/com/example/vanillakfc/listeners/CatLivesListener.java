package com.example.vanillakfc.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cats have {@link #MAX_HEALTH_MULTIPLIER}x vanilla's health, and the nine
 * lives the saying gives them. A blow that would kill a cat instead spends a
 * life: the damage is cancelled, the cat comes back at full health, and the
 * Totem of Undying sound plays. The ninth such blow is the one that sticks -
 * a cat survives eight deaths and dies on the next, which is what "nine
 * lives" has always meant.
 *
 * <p>Lives are counted down in the cat's own
 * {@link org.bukkit.persistence.PersistentDataContainer} rather than in a map
 * here, so the count rides along with the entity: it survives restarts, chunk
 * unloads and the cat being carried across worlds, and a cat that was down to
 * its last life is still on its last life tomorrow.
 *
 * <p>The count gets its own floating label above the cat, using the same
 * invisible-marker-armor-stand technique {@link PetHealthDisplayListener} and
 * {@link MobHealthDisplayListener} use for health, sat just above where those
 * put theirs so the two stack rather than overlap. It is deliberately a
 * second label and not extra text on the health one: a cat's health label is
 * owned by whichever of those two listeners applies (permanent once tamed,
 * look-triggered while stray), and the lives count wants to be visible either
 * way.
 *
 * <p>Two things happen on a revive beyond the sound, both so that a life is
 * spent on a real threat rather than evaporating:
 *
 * <ul>
 *   <li>the cat is extinguished, since one revived while still burning would
 *       be straight back down to 0 health within a couple of seconds;
 *   <li>it gets the same brief invulnerability window an ordinary hit grants.
 *       Cancelling the damage event means vanilla never sets that window
 *       itself, so without this a lava bath or a stack of same-tick damage
 *       sources could take several lives at once.
 * </ul>
 *
 * <p>Damage a cat has no business walking away from is exempt and kills
 * outright however many lives are left: {@code /kill}, the void, and the
 * world border - the last two because reviving there only feeds the cat back
 * into the same damage next tick. Anything that kills without going through
 * {@link EntityDamageEvent} at all, such as a plugin calling {@code
 * setHealth(0)} directly, likewise can't be caught here.
 */
public final class CatLivesListener implements Listener {

    private static final int STARTING_LIVES = 9;
    private static final double MAX_HEALTH_MULTIPLIER = 2.0;

    private static final long POLL_INTERVAL_TICKS = 1L;
    // Clears the health label's own 0.4, so the two read as stacked lines
    // rather than one printed over the other.
    private static final double LABEL_HEIGHT_ABOVE_CAT = 0.7;

    private static final Set<EntityDamageEvent.DamageCause> UNSURVIVABLE = EnumSet.of(
            EntityDamageEvent.DamageCause.KILL,
            EntityDamageEvent.DamageCause.SUICIDE,
            EntityDamageEvent.DamageCause.VOID,
            EntityDamageEvent.DamageCause.WORLD_BORDER
    );

    private final Plugin plugin;
    private final NamespacedKey livesKey;
    private final NamespacedKey maxHealthKey;
    private final NamespacedKey ownerKey;
    private final Map<UUID, ArmorStand> companions = new HashMap<>();
    private final Map<UUID, String> lastLabelText = new HashMap<>();
    private BukkitTask task;

    public CatLivesListener(Plugin plugin) {
        this.plugin = plugin;
        this.livesKey = new NamespacedKey(plugin, "cat_lives");
        this.maxHealthKey = new NamespacedKey(plugin, "cat_max_health");
        this.ownerKey = new NamespacedKey(plugin, "cat_lives_label_owner");
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, POLL_INTERVAL_TICKS, POLL_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // Spending a life has to beat the damage actually landing, so this runs
    // at HIGHEST - late enough that every other plugin has finished adjusting
    // the number, early enough to still cancel it.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCatDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Cat cat)) return;
        if (UNSURVIVABLE.contains(event.getCause())) return;
        if (cat.getHealth() - event.getFinalDamage() > 0.0) return; // survives it anyway

        int lives = livesOf(cat);
        if (lives <= 1) {
            // Out of lives: this is the death that sticks. The totem sound is
            // deliberately not played here - it says "that one didn't count",
            // and this one did.
            setLives(cat, 0);
            return;
        }
        setLives(cat, lives - 1);

        event.setCancelled(true);
        cat.setHealth(maxHealthOf(cat));
        cat.setFireTicks(0);
        cat.setNoDamageTicks(cat.getMaximumNoDamageTicks());
        cat.getWorld().playSound(cat.getLocation(), Sound.ITEM_TOTEM_USE, SoundCategory.NEUTRAL, 1.0f, 1.0f);
    }

    @EventHandler
    public void onCatDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Cat)) return;
        removeCompanion(event.getEntity().getUniqueId());
    }

    private void tick() {
        Set<UUID> liveCats = new HashSet<>();

        for (World world : Bukkit.getWorlds()) {
            for (Cat cat : world.getEntitiesByClass(Cat.class)) {
                // A cat stays in the world through its death animation, and
                // onCatDeath has already taken its label down by then. Without
                // this the next sweep would put a fresh "0 lives" one straight
                // back up over the corpse for the second it takes to vanish.
                if (cat.isDead()) continue;
                liveCats.add(cat.getUniqueId());
                applyMaxHealth(cat);
                updateLabel(cat);
            }
        }

        for (UUID orphaned : new HashSet<>(companions.keySet())) {
            if (!liveCats.contains(orphaned)) {
                removeCompanion(orphaned);
            }
        }
        lastLabelText.keySet().retainAll(liveCats);
    }

    // Applied as a keyed modifier rather than by writing a base value, for two
    // reasons: the key makes it idempotent, so the sweep above can hand it to
    // cats that existed before this listener did without ever doubling twice,
    // and multiplying leaves vanilla free to retune what a cat's health is
    // without this quietly pinning it to whatever that was today.
    private void applyMaxHealth(Cat cat) {
        AttributeInstance maxHealth = cat.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null || hasModifier(maxHealth, maxHealthKey)) return;

        // Raising the ceiling doesn't raise what's in the bar, so a cat that
        // was at full would be left sitting at half. Topped up only if it was
        // full to begin with - a wounded cat stays wounded.
        boolean wasFull = cat.getHealth() >= maxHealth.getValue();
        maxHealth.addModifier(new AttributeModifier(
                maxHealthKey, MAX_HEALTH_MULTIPLIER - 1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        if (wasFull) {
            cat.setHealth(maxHealth.getValue());
        }
    }

    private boolean hasModifier(AttributeInstance instance, NamespacedKey key) {
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (modifier.getKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    private double maxHealthOf(Cat cat) {
        AttributeInstance maxHealth = cat.getAttribute(Attribute.MAX_HEALTH);
        return maxHealth == null ? cat.getHealth() : maxHealth.getValue();
    }

    private int livesOf(Cat cat) {
        Integer stored = cat.getPersistentDataContainer().get(livesKey, PersistentDataType.INTEGER);
        if (stored != null) {
            return stored;
        }
        // First time this cat has been seen - every cat born before this
        // listener existed starts with the full nine.
        setLives(cat, STARTING_LIVES);
        return STARTING_LIVES;
    }

    private void setLives(Cat cat, int lives) {
        cat.getPersistentDataContainer().set(livesKey, PersistentDataType.INTEGER, lives);
    }

    private void updateLabel(Cat cat) {
        ArmorStand label = getOrCreateCompanion(cat);
        label.teleport(cat.getLocation().add(0, cat.getHeight() + LABEL_HEIGHT_ABOVE_CAT, 0));

        String text = livesText(livesOf(cat));
        if (!text.equals(lastLabelText.get(cat.getUniqueId()))) {
            label.customName(Component.text(text, NamedTextColor.GOLD));
            lastLabelText.put(cat.getUniqueId(), text);
        }
    }

    private String livesText(int lives) {
        return lives == 1 ? "1 life" : lives + " lives";
    }

    private ArmorStand getOrCreateCompanion(Cat cat) {
        ArmorStand cached = companions.get(cat.getUniqueId());
        if (cached != null && cached.isValid()) {
            return cached;
        }

        // Not cached - either a fresh boot, or the label got removed some
        // other way. Re-adopt an existing tagged stand before spawning one, so
        // a restart doesn't leave a duplicate floating over every cat.
        for (Entity nearby : cat.getNearbyEntities(2, 2, 2)) {
            if (nearby instanceof ArmorStand stand && isCompanionFor(stand, cat)) {
                companions.put(cat.getUniqueId(), stand);
                return stand;
            }
        }

        ArmorStand created = cat.getWorld().spawn(cat.getLocation(), ArmorStand.class,
                CreatureSpawnEvent.SpawnReason.CUSTOM, false, stand -> {
                    stand.setInvisible(true);
                    stand.setMarker(true);
                    stand.setGravity(false);
                    stand.setInvulnerable(true);
                    stand.setCustomNameVisible(true);
                    stand.setPersistent(true);
                    stand.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING,
                            cat.getUniqueId().toString());
                });
        companions.put(cat.getUniqueId(), created);
        return created;
    }

    private boolean isCompanionFor(ArmorStand stand, Cat cat) {
        String owner = stand.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return cat.getUniqueId().toString().equals(owner);
    }

    private void removeCompanion(UUID catId) {
        ArmorStand stand = companions.remove(catId);
        if (stand != null && stand.isValid()) {
            stand.remove();
        }
        lastLabelText.remove(catId);
    }
}
