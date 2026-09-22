package com.example.vanillakfc;

import io.papermc.paper.datapack.DiscoveredDatapack;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;

import java.net.URI;

/**
 * Registers the datapack bundled at {@code resources/datapack}, which replaces
 * vanilla's {@code minecraft:unbreaking} definition with one that has no
 * {@code item_damage} effect. It also carries two plain data overrides that
 * need no plugin code: the blaze loot table (doubled base rod drop) and the blaze
 * rod's cooking-fuel burn time (16 items instead of 12).
 *
 * <p>Removing the Unbreaking effect hands the entire durability roll to
 * {@link com.example.vanillakfc.listeners.UnbreakingListener}. Vanilla
 * otherwise rolls Unbreaking internally and only raises its durability event
 * for the damage that survived, which means a plugin never hears about the uses
 * vanilla already absorbed and so can only ever reduce damage further, never
 * apply its own shallower curve directly. Removing the effect makes every use
 * visible, so the listener can roll the intended chance itself.
 *
 * <p>This has to happen in a bootstrapper rather than in {@code onEnable},
 * because datapacks and the registries built from them are read before any
 * plugin is enabled.
 *
 * <p>Discovery is not a one-off: the event fires every time the server scans
 * for packs, and Paper's own documentation is explicit that a pack must be
 * re-discovered on each scan or it stops being available. Hence a handler that
 * re-registers rather than a single call at startup.
 */
public class VanillaKFCBootstrap implements PluginBootstrap {

    private static final String PACK_RESOURCE = "/datapack";

    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            try {
                URI uri = VanillaKFCBootstrap.class.getResource(PACK_RESOURCE).toURI();
                DiscoveredDatapack pack = event.registrar().discoverPack(
                        uri, UnbreakingDatapack.PACK_ID, configurer -> configurer
                                .title(Component.text("VanillaKFC"))
                                // Without this the pack sits there needing a
                                // manual /datapack enable, and the listener
                                // would run its fallback in the meantime.
                                .autoEnableOnServerStart(true));
                if (pack != null) {
                    UnbreakingDatapack.discovered(pack.getName());
                }
            } catch (Exception e) {
                // Deliberately not rethrown: a plugin that refuses to load takes
                // every other change in it down too, and the listener's fallback
                // handles a missing pack correctly. Logged loudly instead.
                context.getLogger().error(
                        "Could not register VanillaKFC's Unbreaking datapack; Unbreaking will fall back to"
                                + " scaling vanilla's own roll, which is coarser but still on the right curve."
                                + " Blaze rod drops and burn time stay vanilla.", e);
            }
        });
    }
}
