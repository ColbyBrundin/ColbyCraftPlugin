package com.example.vanillakfc.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tamed pets (Wolf, Cat, Parrot, tamed horses/donkeys/mules/llamas - anything
 * {@link Tameable} once {@code isTamed()}) show their current/max health as
 * an always-visible floating label. This is deliberately a separate label -
 * an invisible marker {@link ArmorStand} hovering just above the pet's own
 * name-tag height - rather than the pet's own custom name: an earlier
 * version wrote the health straight into the pet's customName, which meant
 * it replaced any real name a player had given it and showed up in death
 * messages instead ("14/20 was slain by ..." instead of the pet's actual
 * name, or vanilla's default "Wolf was slain by ..." for an unnamed one).
 * Leaving the entity's own name alone entirely means both a real name tag
 * and vanilla's default death message keep working exactly as they always
 * have.
 *
 * <p>The companion armor stand is tagged with the owning pet's UUID via its
 * {@link org.bukkit.persistence.PersistentDataContainer}, so a fresh scan
 * after a server restart re-adopts the existing one near each pet instead of
 * spawning a duplicate, and it's removed the moment its pet dies rather than
 * waiting for the next scan to notice. {@link #repairPollutedName} also
 * detects and fixes any pet whose customName still carries the old
 * implementation's damage (either "14/20" alone, or "RealName (14/20)"),
 * restoring it to just the real name, or clearing it if it never had one.
 *
 * <p>Repositioning runs every tick rather than on a slower poll: teleporting
 * only every few ticks left the label sitting still wherever the pet was at
 * the last update while the pet itself kept walking, then snapping to catch
 * up - visibly choppy rather than tracking continuously. The health text
 * itself is cheap to recompute every tick too, but is only actually applied
 * (an entity metadata update) when it's changed, to avoid resending the same
 * value 20 times a second.
 */
public final class PetHealthDisplayListener implements Listener {

    private static final long POLL_INTERVAL_TICKS = 1L;
    private static final double LABEL_HEIGHT_ABOVE_PET = 0.4;

    // Matches what this listener's earlier, buggy implementation would have
    // written into a pet's own customName, so it can be repaired on sight.
    private static final Pattern POLLUTED_WITH_BASE_NAME = Pattern.compile("^(.*) \\(\\d+/\\d+\\)$");
    private static final Pattern POLLUTED_HEALTH_ONLY = Pattern.compile("^\\d+/\\d+$");

    private final Plugin plugin;
    private final NamespacedKey ownerKey;
    private final Map<UUID, ArmorStand> companions = new HashMap<>();
    private final Map<UUID, String> lastLabelText = new HashMap<>();
    private BukkitTask task;

    public PetHealthDisplayListener(Plugin plugin) {
        this.plugin = plugin;
        this.ownerKey = new NamespacedKey(plugin, "pet_health_label_owner");
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

    @EventHandler
    public void onPetDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Tameable tameable) || !tameable.isTamed()) return;
        removeCompanion(event.getEntity().getUniqueId());
    }

    private void tick() {
        Set<UUID> livePets = new HashSet<>();

        for (World world : Bukkit.getWorlds()) {
            for (Tameable tameable : world.getEntitiesByClass(Tameable.class)) {
                if (!tameable.isTamed() || !(tameable instanceof LivingEntity pet)) {
                    continue;
                }
                livePets.add(pet.getUniqueId());
                repairPollutedName(pet);
                updateLabel(pet);
            }
        }

        for (UUID orphaned : new HashSet<>(companions.keySet())) {
            if (!livePets.contains(orphaned)) {
                removeCompanion(orphaned);
            }
        }
        lastLabelText.keySet().retainAll(livePets);
    }

    private void updateLabel(LivingEntity pet) {
        ArmorStand label = getOrCreateCompanion(pet);
        label.teleport(pet.getLocation().add(0, pet.getHeight() + LABEL_HEIGHT_ABOVE_PET, 0));

        String text = healthText(pet);
        if (!text.equals(lastLabelText.get(pet.getUniqueId()))) {
            label.customName(Component.text(text));
            lastLabelText.put(pet.getUniqueId(), text);
        }
    }

    private ArmorStand getOrCreateCompanion(LivingEntity pet) {
        ArmorStand cached = companions.get(pet.getUniqueId());
        if (cached != null && cached.isValid()) {
            return cached;
        }

        // Not cached - either a fresh boot, or the label got removed some
        // other way. Check for an existing tagged companion nearby before
        // spawning a new one, so a server restart doesn't leave duplicates
        // floating around.
        for (Entity nearby : pet.getNearbyEntities(2, 2, 2)) {
            if (nearby instanceof ArmorStand stand && isCompanionFor(stand, pet)) {
                companions.put(pet.getUniqueId(), stand);
                return stand;
            }
        }

        ArmorStand created = pet.getWorld().spawn(pet.getLocation(), ArmorStand.class,
                CreatureSpawnEvent.SpawnReason.CUSTOM, false, stand -> {
                    stand.setInvisible(true);
                    stand.setMarker(true);
                    stand.setGravity(false);
                    stand.setInvulnerable(true);
                    stand.setCustomNameVisible(true);
                    stand.setPersistent(true);
                    stand.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, pet.getUniqueId().toString());
                });
        companions.put(pet.getUniqueId(), created);
        return created;
    }

    private boolean isCompanionFor(ArmorStand stand, LivingEntity pet) {
        String owner = stand.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return pet.getUniqueId().toString().equals(owner);
    }

    private void removeCompanion(UUID petId) {
        ArmorStand stand = companions.remove(petId);
        if (stand != null && stand.isValid()) {
            stand.remove();
        }
        lastLabelText.remove(petId);
    }

    // An earlier version of this listener wrote the health straight into
    // the pet's own customName - repair that back to the pet's real name
    // (or clear it, if it never had one) the moment it's noticed. Rebuilt
    // as plain text rather than surgically edited, so any original color/
    // formatting on a "RealName (14/20)" case is lost - an acceptable,
    // one-time cost of correcting a self-inflicted, test-server-only bug.
    private void repairPollutedName(LivingEntity pet) {
        Component customName = pet.customName();
        if (customName == null) return;

        String plain = PlainTextComponentSerializer.plainText().serialize(customName);
        Matcher withBase = POLLUTED_WITH_BASE_NAME.matcher(plain);
        if (withBase.matches()) {
            pet.customName(Component.text(withBase.group(1)));
        } else if (POLLUTED_HEALTH_ONLY.matcher(plain).matches()) {
            pet.customName(null);
        }
    }

    private String healthText(LivingEntity pet) {
        int current = (int) Math.ceil(pet.getHealth());
        AttributeInstance maxHealthAttribute = pet.getAttribute(Attribute.MAX_HEALTH);
        int max = maxHealthAttribute == null ? current : (int) Math.round(maxHealthAttribute.getValue());
        return current + "/" + max;
    }
}
