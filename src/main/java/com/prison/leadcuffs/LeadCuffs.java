package com.prison.leadcuffs;

import org.bukkit.plugin.java.JavaPlugin;

public class LeadCuffs extends JavaPlugin {

    private CuffManager cuffManager;

    @Override
    public void onEnable() {
        cuffManager = new CuffManager(this);

        getServer().getPluginManager().registerEvents(new CuffListener(this, cuffManager), this);
        getCommand("uncuff").setExecutor(new UncuffCommand(cuffManager));

        getLogger().info("LeadCuffs enabled! Right-click a player with a lead to cuff them.");
    }

    @Override
    public void onDisable() {
        // Release all cuffed players on shutdown
        cuffManager.releaseAll();
        getLogger().info("LeadCuffs disabled. All players released.");
    }
}
