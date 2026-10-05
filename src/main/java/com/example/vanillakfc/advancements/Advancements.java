package com.example.vanillakfc.advancements;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;

/**
 * Awards this plugin's own advancements.
 *
 * <p>Every advancement the plugin owns lives in the bundled datapack under
 * {@code data/vanillakfc/advancement} and holds a single
 * {@code minecraft:impossible} criterion named {@value #CRITERION}, which is
 * the standard way to leave the "when" to plugin code. Awarding one is
 * therefore always the same two steps - resolve it, award that criterion -
 * and this is where they live so each listener only has to say which one and
 * for whom.
 *
 * <p>A missing advancement means the datapack is disabled or failed to load.
 * That is worth saying once per advancement rather than on every attempt,
 * hence the set of keys already complained about.
 */
public final class Advancements {

    /** The criterion name every plugin advancement uses. */
    public static final String CRITERION = "earned";

    private final Plugin plugin;
    private final Set<NamespacedKey> warned = new HashSet<>();

    public Advancements(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param name the advancement's file name, e.g. {@code dying_grace} for
     *             {@code vanillakfc:dying_grace}
     * @return true if this call is what earned it - false if it was already
     *         done, or the advancement isn't loaded
     */
    public boolean award(Player player, String name) {
        // The namespace comes from the plugin's own name, which is what makes
        // this line up with the datapack's vanillakfc: folder.
        NamespacedKey key = new NamespacedKey(plugin, name);
        Advancement advancement = Bukkit.getAdvancement(key);
        if (advancement == null) {
            if (warned.add(key)) {
                plugin.getLogger().warning("Advancement " + key + " is missing - the bundled datapack is disabled or"
                        + " failed to load, so it can't be awarded.");
            }
            return false;
        }

        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        if (progress.isDone()) return false;
        return progress.awardCriteria(CRITERION);
    }
}
