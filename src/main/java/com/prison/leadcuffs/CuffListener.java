package com.prison.leadcuffs;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.UUID;

/**
 * Listens for right-click (ПКМ) interactions on players with a lead in hand,
 * blocks movement for cuffed players, and prevents damage to cuffed players from captor.
 */
public class CuffListener implements Listener {

    private final LeadCuffs plugin;
    private final CuffManager cuffManager;

    public CuffListener(LeadCuffs plugin, CuffManager cuffManager) {
        this.plugin = plugin;
        this.cuffManager = cuffManager;
    }

    /**
     * RIGHT-CLICK (ПКМ) on a player entity while holding a lead.
     * This event fires for right-clicking on entities.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        // Only handle main hand to avoid firing twice
        if (event.getHand() != EquipmentSlot.HAND) return;

        // Only players
        if (!(event.getRightClicked() instanceof Player target)) return;

        Player captor = event.getPlayer();
        plugin.getLogger().info("[LeadCuffs] PlayerInteractEntityEvent: " + captor.getName() + " -> " + target.getName()
                + " | MainHand: " + captor.getInventory().getItemInMainHand().getType());

        // Check if holding a lead
        Material mainHandType = captor.getInventory().getItemInMainHand().getType();
        if (mainHandType != Material.LEAD) return;

        // Cancel default lead behavior FIRST
        event.setCancelled(true);

        // Permission check
        if (!captor.hasPermission("leadcuffs.use")) {
            captor.sendMessage(ChatColor.RED + "У вас нет прав для использования наручников!");
            return;
        }

        // Bypass check
        if (target.hasPermission("leadcuffs.bypass")) {
            captor.sendMessage(ChatColor.RED + "Этого игрока нельзя сковать!");
            return;
        }

        // Can't cuff yourself
        if (captor.getUniqueId().equals(target.getUniqueId())) {
            captor.sendMessage(ChatColor.RED + "Вы не можете сковать самого себя!");
            return;
        }

        // If already cuffed by this captor — release
        if (cuffManager.isCuffed(target.getUniqueId())) {
            UUID currentCaptor = cuffManager.getCaptor(target.getUniqueId());
            if (currentCaptor.equals(captor.getUniqueId())) {
                cuffManager.release(target.getUniqueId());
                captor.sendMessage(ChatColor.GREEN + "✔ Вы сняли наручники с " + ChatColor.YELLOW + target.getName());
                target.sendMessage(ChatColor.GREEN + "✔ " + ChatColor.YELLOW + captor.getName() + ChatColor.GREEN + " снял с вас наручники. Вы свободны!");
                return;
            } else {
                captor.sendMessage(ChatColor.RED + "Этот игрок скован другим игроком!");
                return;
            }
        }

        // Can't cuff if you're cuffed
        if (cuffManager.isCuffed(captor.getUniqueId())) {
            captor.sendMessage(ChatColor.RED + "Вы скованы! Сначала освободитесь.");
            return;
        }

        // --- CUFF ---
        cuffManager.cuff(captor, target);
        captor.sendMessage(ChatColor.GOLD + "⛓ Вы сковали игрока " + ChatColor.YELLOW + target.getName()
                + ChatColor.GOLD + "! ПКМ по нему ещё раз — снять.");
        target.sendMessage(ChatColor.RED + "⛓ " + ChatColor.YELLOW + captor.getName()
                + ChatColor.RED + " сковал вас наручниками! Вы не можете убежать.");
    }

    /**
     * BLOCK MOVEMENT — the core restriction.
     * If the prisoner tries to move AWAY from captor beyond the leash radius, cancel the move.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (!cuffManager.isCuffed(playerId)) return;

        UUID captorId = cuffManager.getCaptor(playerId);
        if (captorId == null) return;

        Player captor = Bukkit.getPlayer(captorId);
        if (captor == null || !captor.isOnline()) {
            cuffManager.release(playerId);
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        // Allow pure head rotation (no position change)
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }

        // Must be same world
        if (!player.getWorld().equals(captor.getWorld())) return;

        Location captorLoc = captor.getLocation();
        double distFrom = from.distance(captorLoc);
        double distTo = to.distance(captorLoc);

        // If already at or beyond radius AND trying to move further away — block
        if (distTo > CuffManager.LEASH_RADIUS && distTo >= distFrom) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevent sprinting while cuffed.
     */
    @EventHandler
    public void onSprint(PlayerToggleSprintEvent event) {
        if (event.isSprinting() && cuffManager.isCuffed(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevent cuffed player from being damaged by their captor (no accidental hits).
     */
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player damager)) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        if (cuffManager.isCuffed(victim.getUniqueId())) {
            UUID captor = cuffManager.getCaptor(victim.getUniqueId());
            if (captor != null && captor.equals(damager.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Clean up on disconnect — release cuffed prisoners and prisoners of disconnecting captors.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();

        // Release if cuffed
        if (cuffManager.isCuffed(playerId)) {
            cuffManager.release(playerId);
        }

        // Release all prisoners if their captor left
        for (var entry : new HashMap<>(cuffManager.getCuffedPlayers()).entrySet()) {
            if (entry.getValue().equals(playerId)) {
                cuffManager.release(entry.getKey());
                Player prisoner = Bukkit.getPlayer(entry.getKey());
                if (prisoner != null && prisoner.isOnline()) {
                    prisoner.sendMessage(ChatColor.GREEN + "✔ Ваш конвоир вышел. Вы свободны!");
                }
            }
        }
    }
}
