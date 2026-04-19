package com.prison.leadcuffs;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages cuffed players — tracks who is cuffed to whom,
 * runs the follow task, and handles release logic.
 */
public class CuffManager {

    private final LeadCuffs plugin;

    // cuffed player UUID -> captor player UUID
    private final Map<UUID, UUID> cuffedPlayers = new HashMap<>();

    // cuffed player UUID -> their follow task ID
    private final Map<UUID, Integer> followTasks = new HashMap<>();

    // Maximum distance before teleporting the prisoner closer
    private static final double MAX_DISTANCE = 5.0;

    // How close to keep the prisoner behind the captor
    private static final double FOLLOW_DISTANCE = 2.5;

    public CuffManager(LeadCuffs plugin) {
        this.plugin = plugin;
    }

    /**
     * Cuff a target player to a captor.
     */
    public void cuff(Player captor, Player target) {
        UUID captorId = captor.getUniqueId();
        UUID targetId = target.getUniqueId();

        cuffedPlayers.put(targetId, captorId);

        // Disable target's ability to sprint
        target.setSprinting(false);

        // Visual & audio feedback
        target.getWorld().playSound(target.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.8f);
        captor.getWorld().playSound(captor.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.8f);

        // Start the follow task — runs every 2 ticks (100ms)
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                Player prisoner = Bukkit.getPlayer(targetId);
                Player holder = Bukkit.getPlayer(captorId);

                // If either player is offline, release
                if (prisoner == null || holder == null || !prisoner.isOnline() || !holder.isOnline()) {
                    release(targetId);
                    return;
                }

                // If they are in different worlds, teleport prisoner
                if (!prisoner.getWorld().equals(holder.getWorld())) {
                    prisoner.teleport(holder.getLocation());
                    return;
                }

                double distance = prisoner.getLocation().distance(holder.getLocation());

                // If prisoner is too far away, pull them back
                if (distance > MAX_DISTANCE) {
                    Location holderLoc = holder.getLocation();
                    // Calculate a position behind the captor
                    Location behindHolder = holderLoc.clone().add(
                            holderLoc.getDirection().normalize().multiply(-FOLLOW_DISTANCE)
                    );
                    behindHolder.setY(holderLoc.getY());

                    // Preserve the prisoner's look direction
                    behindHolder.setYaw(prisoner.getLocation().getYaw());
                    behindHolder.setPitch(prisoner.getLocation().getPitch());

                    prisoner.teleport(behindHolder);
                    prisoner.getWorld().spawnParticle(Particle.SMOKE, prisoner.getLocation().add(0, 1, 0), 5, 0.2, 0.2, 0.2, 0.01);
                } else if (distance > MAX_DISTANCE - 1.0) {
                    // When getting close to the limit, slow them down
                    prisoner.setWalkSpeed(0.05f); // Very slow
                } else {
                    // Normal restricted speed (slower than default 0.2)
                    prisoner.setWalkSpeed(0.12f);
                }

                // Prevent sprinting
                if (prisoner.isSprinting()) {
                    prisoner.setSprinting(false);
                }
            }
        };

        int taskId = task.runTaskTimer(plugin, 0L, 2L).getTaskId();
        followTasks.put(targetId, taskId);
    }

    /**
     * Release a cuffed player by their UUID.
     */
    public void release(UUID targetId) {
        if (!cuffedPlayers.containsKey(targetId)) return;

        cuffedPlayers.remove(targetId);

        // Cancel the follow task
        Integer taskId = followTasks.remove(targetId);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }

        // Restore player state
        Player prisoner = Bukkit.getPlayer(targetId);
        if (prisoner != null && prisoner.isOnline()) {
            prisoner.setWalkSpeed(0.2f); // Default walk speed
            prisoner.getWorld().playSound(prisoner.getLocation(), Sound.BLOCK_CHAIN_BREAK, 1.0f, 1.2f);
            prisoner.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, prisoner.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0.01);
        }
    }

    /**
     * Release all cuffed players (used on plugin disable).
     */
    public void releaseAll() {
        for (UUID targetId : new HashMap<>(cuffedPlayers).keySet()) {
            release(targetId);
        }
    }

    /**
     * Check if a player is currently cuffed.
     */
    public boolean isCuffed(UUID playerId) {
        return cuffedPlayers.containsKey(playerId);
    }

    /**
     * Get the captor UUID for a cuffed player.
     */
    public UUID getCaptor(UUID playerId) {
        return cuffedPlayers.get(playerId);
    }

    /**
     * Get all cuffed players map.
     */
    public Map<UUID, UUID> getCuffedPlayers() {
        return cuffedPlayers;
    }
}
