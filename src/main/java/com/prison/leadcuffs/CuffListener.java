package com.prison.leadcuffs;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.UUID;

/**
 * Listens for right-click interactions on players with a lead in hand,
 * and blocks movement for cuffed players beyond the leash radius.
 */
public class CuffListener implements Listener {

    private final LeadCuffs plugin;
    private final CuffManager cuffManager;

    public CuffListener(LeadCuffs plugin, CuffManager cuffManager) {
        this.plugin = plugin;
        this.cuffManager = cuffManager;
    }

    /**
     * PlayerInteractEntityEvent fires on RIGHT-CLICK (ПКМ) on an entity.
     * This is the event we use to cuff/uncuff players with a lead.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        // Only process if the clicked entity is a player
        if (!(event.getRightClicked() instanceof Player target)) return;

        Player captor = event.getPlayer();

        // Check if the captor is holding a lead in main hand or off hand
        ItemStack mainHand = captor.getInventory().getItemInMainHand();
        ItemStack offHand = captor.getInventory().getItemInOffHand();

        boolean hasLeadMain = mainHand != null && mainHand.getType() == Material.LEAD;
        boolean hasLeadOff = offHand != null && offHand.getType() == Material.LEAD;

        if (!hasLeadMain && !hasLeadOff) return;

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
     * Block movement when the cuffed player tries to move beyond the leash radius.
     * This is the key mechanic that makes the cuffs feel solid.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player prisoner = event.getPlayer();

        if (!cuffManager.isCuffed(prisoner.getUniqueId())) return;

        UUID captorId = cuffManager.getCaptor(prisoner.getUniqueId());
        Player captor = Bukkit.getPlayer(captorId);

        if (captor == null || !captor.isOnline()) {
            cuffManager.release(prisoner.getUniqueId());
            return;
        }

        // Allow looking around (head rotation) but block actual position movement
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // If only head rotation changed, allow it
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        // Check if the player is in the same world as captor
        if (!prisoner.getWorld().equals(captor.getWorld())) return;

        double currentDistance = from.distance(captor.getLocation());
        double newDistance = to.distance(captor.getLocation());

        // If the prisoner is trying to move AWAY from the captor beyond the leash radius, cancel it
        if (newDistance > cuffManager.getLeashRadius() && newDistance > currentDistance) {
            // Cancel movement but keep head rotation
            Location blocked = from.clone();
            blocked.setYaw(to.getYaw());
            blocked.setPitch(to.getPitch());
            event.setTo(blocked);
        }
    }

    /**
     * Prevent cuffed players from sprinting.
     */
    @EventHandler
    public void onPlayerToggleSprint(PlayerToggleSprintEvent event) {
        if (cuffManager.isCuffed(event.getPlayer().getUniqueId())) {
            if (event.isSprinting()) {
                event.setCancelled(true);
            }
        }
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
        for (var entry : new HashMap<>(cuffManager.getCuffedPlayers()).entrySet()) {
            if (entry.getValue().equals(player.getUniqueId())) {
                cuffManager.release(entry.getKey());

                Player prisoner = Bukkit.getPlayer(entry.getKey());
                if (prisoner != null && prisoner.isOnline()) {
                    prisoner.sendMessage(ChatColor.GREEN + "✔ Ваш конвоир вышел. Вы свободны!");
                }
            }
        }
    }
}
