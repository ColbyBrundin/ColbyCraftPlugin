package com.example.vanillakfc;

import com.example.vanillakfc.listeners.AnvilRepairListener;
import com.example.vanillakfc.listeners.BedExplosionListener;
import com.example.vanillakfc.listeners.BreedingGrowthListener;
import com.example.vanillakfc.listeners.CatLivesListener;
import com.example.vanillakfc.listeners.CreeperBehaviorTask;
import com.example.vanillakfc.listeners.CreeperListener;
import com.example.vanillakfc.listeners.ConcretePowderListener;
import com.example.vanillakfc.listeners.CropFortuneListener;
import com.example.vanillakfc.listeners.CropGrowthListener;
import com.example.vanillakfc.debug.LightLevelDebug;
import com.example.vanillakfc.listeners.DeathDropListener;
import com.example.vanillakfc.listeners.DrownedTridentListener;
import com.example.vanillakfc.listeners.DurabilityListener;
import com.example.vanillakfc.listeners.ElytraEnchantListener;
import com.example.vanillakfc.listeners.EndCityLootListener;
import com.example.vanillakfc.listeners.EndermanDodgeSoundPackListener;
import com.example.vanillakfc.listeners.EndermanListener;
import com.example.vanillakfc.listeners.FishingLootListener;
import com.example.vanillakfc.listeners.FoodSaturationListener;
import com.example.vanillakfc.listeners.FortuneLootListener;
import com.example.vanillakfc.listeners.MobHealthDisplayListener;
import com.example.vanillakfc.listeners.HappyGhastListener;
import com.example.vanillakfc.listeners.IronGolemListener;
import com.example.vanillakfc.listeners.LightningListener;
import com.example.vanillakfc.listeners.MaceDamageListener;
import com.example.vanillakfc.listeners.NetherPortalScaleListener;
import com.example.vanillakfc.listeners.NetherRoofVoidDamageListener;
import com.example.vanillakfc.listeners.PetHealthDisplayListener;
import com.example.vanillakfc.listeners.PodSpawnCooldownListener;
import com.example.vanillakfc.listeners.RecipeDiscoveryListener;
import com.example.vanillakfc.listeners.SkeletonListener;
import com.example.vanillakfc.listeners.SleepStreakListener;
import com.example.vanillakfc.listeners.SpearDamageListener;
import com.example.vanillakfc.listeners.SpiderListener;
import com.example.vanillakfc.listeners.TamedWolfListener;
import com.example.vanillakfc.listeners.UnbreakingListener;
import com.example.vanillakfc.listeners.VillagerTradeListener;
import com.example.vanillakfc.listeners.WeaponStatListener;
import com.example.vanillakfc.listeners.XpCapListener;
import com.example.vanillakfc.listeners.ZombiePodListener;
import com.example.vanillakfc.recipes.CustomRecipes;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class VanillaKFC extends JavaPlugin {

    private CreeperBehaviorTask creeperBehaviorTask;
    private EndermanDodgeSoundPackListener endermanDodgeSoundPackListener;
    private LightningListener lightningListener;
    private NetherRoofVoidDamageListener netherRoofVoidDamageListener;
    private ConcretePowderListener concretePowderListener;
    private PetHealthDisplayListener petHealthDisplayListener;
    private HappyGhastListener happyGhastListener;
    private CatLivesListener catLivesListener;
    private TamedWolfListener tamedWolfListener;
    private MobHealthDisplayListener mobHealthDisplayListener;
    private RecipeDiscoveryListener recipeDiscoveryListener;
    private SleepStreakListener sleepStreakListener;
    private LightLevelDebug lightLevelDebug;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new DeathDropListener(this), this);
        getServer().getPluginManager().registerEvents(new DurabilityListener(), this);
        getServer().getPluginManager().registerEvents(new BedExplosionListener(), this);
        getServer().getPluginManager().registerEvents(new CreeperListener(), this);
        getServer().getPluginManager().registerEvents(new IronGolemListener(), this);
        getServer().getPluginManager().registerEvents(new EndermanListener(this), this);
        getServer().getPluginManager().registerEvents(new DrownedTridentListener(), this);
        getServer().getPluginManager().registerEvents(new SpiderListener(), this);
        getServer().getPluginManager().registerEvents(new ZombiePodListener(), this);
        getServer().getPluginManager().registerEvents(new PodSpawnCooldownListener(), this);
        getServer().getPluginManager().registerEvents(new SkeletonListener(this), this);
        getServer().getPluginManager().registerEvents(new WeaponStatListener(), this);
        getServer().getPluginManager().registerEvents(new ElytraEnchantListener(), this);
        getServer().getPluginManager().registerEvents(new MaceDamageListener(), this);
        getServer().getPluginManager().registerEvents(new SpearDamageListener(), this);
        getServer().getPluginManager().registerEvents(new AnvilRepairListener(), this);
        getServer().getPluginManager().registerEvents(new VillagerTradeListener(this), this);
        getServer().getPluginManager().registerEvents(new XpCapListener(), this);
        sleepStreakListener = new SleepStreakListener(this);
        getServer().getPluginManager().registerEvents(sleepStreakListener, this);
        sleepStreakListener.start();

        lightLevelDebug = new LightLevelDebug(this);
        getServer().getPluginManager().registerEvents(lightLevelDebug, this);
        // Paper plugins have no commands: block and no getCommand(); the
        // registrar event is where a command tree gets handed over, and it
        // re-fires on /reload so it has to be a handler, not a one-off call.
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(lightLevelDebug.buildCommand(),
                        "Toggle a private overlay marking nearby spots dark enough for hostile mobs to spawn."));
        lightLevelDebug.start();
        getServer().getPluginManager().registerEvents(new CropGrowthListener(), this);
        getServer().getPluginManager().registerEvents(new FoodSaturationListener(), this);
        getServer().getPluginManager().registerEvents(new BreedingGrowthListener(), this);
        getServer().getPluginManager().registerEvents(new NetherPortalScaleListener(), this);
        getServer().getPluginManager().registerEvents(new FishingLootListener(), this);
        getServer().getPluginManager().registerEvents(new FortuneLootListener(), this);
        getServer().getPluginManager().registerEvents(new CropFortuneListener(), this);
        getServer().getPluginManager().registerEvents(new EndCityLootListener(), this);
        getServer().getPluginManager().registerEvents(new ResourcePackStatusLogger(), this);
        CustomRecipes.registerAll(this, getLogger());

        // Must come after registerAll - it reads the registry that call fills.
        recipeDiscoveryListener = new RecipeDiscoveryListener(this);
        getServer().getPluginManager().registerEvents(recipeDiscoveryListener, this);
        recipeDiscoveryListener.start();

        creeperBehaviorTask = new CreeperBehaviorTask(this);
        getServer().getPluginManager().registerEvents(creeperBehaviorTask, this);
        creeperBehaviorTask.start();

        getServer().getPluginManager().registerEvents(new UnbreakingListener(), this);

        endermanDodgeSoundPackListener = new EndermanDodgeSoundPackListener();
        getServer().getPluginManager().registerEvents(endermanDodgeSoundPackListener, this);
        endermanDodgeSoundPackListener.start(this, getFile());

        lightningListener = new LightningListener(this);
        lightningListener.start();

        netherRoofVoidDamageListener = new NetherRoofVoidDamageListener(this);
        netherRoofVoidDamageListener.start();

        concretePowderListener = new ConcretePowderListener(this);
        concretePowderListener.start();

        petHealthDisplayListener = new PetHealthDisplayListener(this);
        getServer().getPluginManager().registerEvents(petHealthDisplayListener, this);
        petHealthDisplayListener.start();

        tamedWolfListener = new TamedWolfListener(this);
        getServer().getPluginManager().registerEvents(tamedWolfListener, this);
        tamedWolfListener.start();

        mobHealthDisplayListener = new MobHealthDisplayListener(this);
        mobHealthDisplayListener.start();

        catLivesListener = new CatLivesListener(this);
        getServer().getPluginManager().registerEvents(catLivesListener, this);
        catLivesListener.start();

        happyGhastListener = new HappyGhastListener(this);
        happyGhastListener.start();

        getLogger().info("Unbreaking datapack " + UnbreakingDatapack.describe() + ".");
        getLogger().info("VanillaKFC has been enabled!");
    }

    @Override
    public void onDisable() {
        if (creeperBehaviorTask != null) {
            creeperBehaviorTask.stop();
        }
        if (endermanDodgeSoundPackListener != null) {
            endermanDodgeSoundPackListener.stop();
        }
        if (lightningListener != null) {
            lightningListener.stop();
        }
        if (netherRoofVoidDamageListener != null) {
            netherRoofVoidDamageListener.stop();
        }
        if (concretePowderListener != null) {
            concretePowderListener.stop();
        }
        if (petHealthDisplayListener != null) {
            petHealthDisplayListener.stop();
        }
        if (tamedWolfListener != null) {
            tamedWolfListener.stop();
        }
        if (mobHealthDisplayListener != null) {
            mobHealthDisplayListener.stop();
        }
        if (catLivesListener != null) {
            catLivesListener.stop();
        }
        if (happyGhastListener != null) {
            happyGhastListener.stop();
        }
        if (sleepStreakListener != null) {
            sleepStreakListener.stop();
        }
        if (lightLevelDebug != null) {
            lightLevelDebug.stop();
        }
        if (recipeDiscoveryListener != null) {
            recipeDiscoveryListener.stop();
        }
        getLogger().info("VanillaKFC has been disabled!");
    }

    // Temporary diagnostic: the client gives no visible feedback when a
    // server-wide resource pack (set via server.properties) silently fails
    // to load, so this logs every player's actual pack status to the
    // console - the only way to tell a real load failure apart from the
    // Enderman dodge sound just not having rolled yet.
    private class ResourcePackStatusLogger implements Listener {
        @EventHandler
        public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
            getLogger().info("Resource pack status for " + event.getPlayer().getName()
                    + ": " + event.getStatus() + " (id=" + event.getID() + ")");
        }
    }
}
