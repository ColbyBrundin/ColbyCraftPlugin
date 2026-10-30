package com.example.vanillakfc.listeners;

import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Shows or hides a mob's glowing outline for one player only.
 *
 * <p>The outline isn't drawn from the Glowing effect itself: the client draws
 * it from a bit in the entity's shared flags byte, which the server sends to
 * everyone tracking the entity. So the API's only switch,
 * {@code Entity#setGlowing}, is everyone or no one, and sending one player
 * the effect ({@code Player#sendPotionEffectChange}) changes nothing on
 * screen. What works is sending that one player the flags byte with the
 * glowing bit set - the same packet the server would send, just to them.
 *
 * <p>The rest of the byte (on fire, invisible, and so on) is the entity's
 * real current value, so nothing else about it changes for that player. The
 * server re-sends the true byte whenever one of those flags changes, and
 * with the entity's spawn data when it comes into view, which drops the
 * glow again for that player - {@link WardenHelmetListener} re-sends it
 * every second to cover that.
 *
 * <p>Server internals, compiled against the server jar (see
 * build.gradle.kts), so this needs revisiting when Paper is updated.
 */
final class WardenGlow {

    // The flags byte is the first synced field every entity has.
    private static final int SHARED_FLAGS_ID = 0;
    private static final byte GLOWING_BIT = 1 << 6;

    private WardenGlow() {
    }

    static void show(Player viewer, Entity target) {
        send(viewer, target, true);
    }

    /** Back to whatever the entity really shows - glowing, if it truly is. */
    static void hide(Player viewer, Entity target) {
        send(viewer, target, false);
    }

    private static void send(Player viewer, Entity target, boolean glowing) {
        net.minecraft.world.entity.Entity handle = ((CraftEntity) target).getHandle();
        byte flags = currentFlags(handle);
        if (glowing) flags |= GLOWING_BIT;
        ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(handle.getId(),
                List.of(new SynchedEntityData.DataValue<>(SHARED_FLAGS_ID, EntityDataSerializers.BYTE, flags)));
        ((CraftPlayer) viewer).getHandle().connection.send(packet);
    }

    // Read off the synced values rather than rebuilt from getters, so it is
    // exactly what the server last sent. A byte still at its default (0)
    // isn't listed.
    private static byte currentFlags(net.minecraft.world.entity.Entity handle) {
        List<SynchedEntityData.DataValue<?>> values = handle.getEntityData().getNonDefaultValues();
        if (values != null) {
            for (SynchedEntityData.DataValue<?> value : values) {
                if (value.id() == SHARED_FLAGS_ID && value.value() instanceof Byte flags) {
                    return flags;
                }
            }
        }
        return 0;
    }
}
