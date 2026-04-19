package com.prison.leadcuffs;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

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

    // Max distance the prisoner can be from captor before being pulled
    private static final double LEASH_RADIUS = 3.5;

    // Distance at which the prisoner gets teleported (too far, e.g. different chunk loaded)
    private static final double TELEPORT_DISTANCE = 15.0;

    // How far behind the captor to place the prisoner on teleport
    private static final double BEHIND_DISTANCE = 1.5;

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

        // Disable sprinting and slow down
        target.setSprinting(false);
        target.setWalkSpeed(0.1f); // Slower than default 0.2

        // Visual & audio feedback
        target.getWorld().playSound(target.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.8f);
        captor.getWorld().playSound(captor.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.8f);

        // Start the follow/pull task — runs every tick for smooth pulling
        BukkitRunnable task = new BukkitRunnable() {
            private int tickCounter = 0;

            @Override
            public void run() {
                tickCounter++;

                Player prisoner = Bukkit.getPlayer(targetId);
                Player holder = Bukkit.getPlayer(captorId);

                // If either player is offline, release
                if (prisoner == null || holder == null || !prisoner.isOnline() || !holder.isOnline()) {
                    release(targetId);
                    return;
                }

                // If in different worlds, teleport prisoner
                if (!prisoner.getWorld().equals(holder.getWorld())) {
                    prisoner.teleport(holder.getLocation());
                    return;
                }

                Location prisonerLoc = prisoner.getLocation();
                Location holderLoc = holder.getLocation();
                double distance = prisonerLoc.distance(holderLoc);

                // Emergency teleport if way too far
                if (distance > TELEPORT_DISTANCE) {
                    Location behind = getLocationBehind(holder);
                    behind.setYaw(prisonerLoc.getYaw());
                    behind.setPitch(prisonerLoc.getPitch());
                    prisoner.teleport(behind);
                    return;
                }

                // If prisoner is beyond leash radius, pull them with velocity
                if (distance > LEASH_RADIUS) {
                    Vector direction = holderLoc.toVector().subtract(prisonerLoc.toVector()).normalize();
                    double pullStrength = Math.min(0.6, (distance - LEASH_RADIUS) * 0.3);
                    direction.multiply(pullStrength);
                    // Keep some Y so they don't sink into ground
                    direction.setY(Math.max(direction.getY(), 0.0));
                    prisoner.setVelocity(direction);
                }

                // Prevent sprinting always
                if (prisoner.isSprinting()) {
                    prisoner.setSprinting(false);
                }

                // Particle chain effect between prisoner and holder (every 4 ticks)
                if (tickCounter % 4 == 0) {
                    spawnChainParticles(prisonerLoc, holderLoc);
                }
            }
        };

        int taskId = task.runTaskTimer(plugin, 0L, 1L).getTaskId();
        followTasks.put(targetId, taskId);
    }

    /**
     * Spawn particles along the line between two locations to simulate a chain.
     */
    private void spawnChainParticles(Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector());
        double length = direction.length();
        if (length < 1.0) return;

        direction.normalize();
        // one particle every 0.8 blocks
        for (double d = 0; d < length; d += 0.8) {
            Location point = from.clone().add(direction.clone().multiply(d)).add(0, 1.0, 0);
            from.getWorld().spawnParticle(Particle.CRIT, point, 1, 0.05, 0.05, 0.05, 0);
        }
    }

    /**
     * Get a location behind the player (based on their facing direction).
     */
    private Location getLocationBehind(Player player) {
        Location loc = player.getLocation().clone();
        Vector behind = loc.getDirection().normalize().multiply(-BEHIND_DISTANCE);
        loc.add(behind);
        loc.setY(player.getLocation().getY());
        return loc;
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
     * Get the leash radius.
     */
    public double getLeashRadius() {
        return LEASH_RADIUS;
    }

    /**
     * Get all cuffed players map.
     */
    public Map<UUID, UUID> getCuffedPlayers() {
        return cuffedPlayers;
    }
}
