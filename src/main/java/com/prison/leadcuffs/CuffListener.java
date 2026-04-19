package com.prison.leadcuffs;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listens for right-click interactions on players with a lead in hand.
 * Right-clicking a player with a lead will toggle the cuff state.
 */
public class CuffListener implements Listener {

    private final LeadCuffs plugin;
    private final CuffManager cuffManager;

    public CuffListener(LeadCuffs plugin, CuffManager cuffManager) {
        this.plugin = plugin;
        this.cuffManager = cuffManager;
    }

    /**
     * PlayerInteractAtEntityEvent fires on RIGHT-CLICK (ПКМ) on an entity.
     * This is the event we use to cuff/uncuff players with a lead.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        // Only process if the clicked entity is a player
        if (!(event.getRightClicked() instanceof Player target)) return;

        Player captor = event.getPlayer();

        // Check if the captor is holding a lead in their main hand
        ItemStack itemInHand = captor.getInventory().getItemInMainHand();
        if (itemInHand.getType() != Material.LEAD) return;

        // Check permission
        if (!captor.hasPermission("leadcuffs.use")) {
            captor.sendMessage(ChatColor.RED + "У вас нет прав для использования наручников!");
            return;
        }

        // Check if target has bypass permission
        if (target.hasPermission("leadcuffs.bypass")) {
            captor.sendMessage(ChatColor.RED + "Этого игрока нельзя сковать!");
            return;
        }

        // Cancel the default interaction (prevents actually leashing)
        event.setCancelled(true);

        // If the target is already cuffed BY THIS captor, release them
        if (cuffManager.isCuffed(target.getUniqueId())) {
            if (cuffManager.getCaptor(target.getUniqueId()).equals(captor.getUniqueId())) {
                // Release
                cuffManager.release(target.getUniqueId());

                captor.sendMessage(ChatColor.GREEN + "✔ Вы сняли наручники с " + ChatColor.YELLOW + target.getName());
                target.sendMessage(ChatColor.GREEN + "✔ " + ChatColor.YELLOW + captor.getName() + ChatColor.GREEN + " снял с вас наручники. Вы свободны!");
            } else {
                captor.sendMessage(ChatColor.RED + "Этот игрок скован другим игроком!");
            }
            return;
        }

        // Can't cuff yourself
        if (captor.getUniqueId().equals(target.getUniqueId())) {
            captor.sendMessage(ChatColor.RED + "Вы не можете сковать самого себя!");
            return;
        }

        // Can't cuff someone if you're cuffed yourself
        if (cuffManager.isCuffed(captor.getUniqueId())) {
            captor.sendMessage(ChatColor.RED + "Вы скованы! Сначала освободитесь.");
            return;
        }

        // Cuff the target
        cuffManager.cuff(captor, target);

        captor.sendMessage(ChatColor.GOLD + "⛓ Вы сковали игрока " + ChatColor.YELLOW + target.getName() + ChatColor.GOLD + "! Нажмите ПКМ ещё раз, чтобы снять наручники.");
        target.sendMessage(ChatColor.RED + "⛓ " + ChatColor.YELLOW + captor.getName() + ChatColor.RED + " сковал вас наручниками! Вы не можете убежать.");
    }

    /**
     * Release cuffed players when they disconnect.
     * Also release prisoners if their captor disconnects.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // If the leaving player was cuffed, release them
        if (cuffManager.isCuffed(player.getUniqueId())) {
            cuffManager.release(player.getUniqueId());
        }

        // If the leaving player was a captor, release all their prisoners
        for (var entry : new java.util.HashMap<>(cuffManager.getCuffedPlayers()).entrySet()) {
            if (entry.getValue().equals(player.getUniqueId())) {
                cuffManager.release(entry.getKey());

                Player prisoner = org.bukkit.Bukkit.getPlayer(entry.getKey());
                if (prisoner != null && prisoner.isOnline()) {
                    prisoner.sendMessage(ChatColor.GREEN + "✔ Ваш конвоир вышел. Вы свободны!");
                }
            }
        }
    }
}
