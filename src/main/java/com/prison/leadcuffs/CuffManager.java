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
import java.util.logging.Logger;

/**
 * Manages cuffed players — tracks who is cuffed to whom,
 * runs the follow task, and handles release logic.
 */
public class CuffManager {

    private final LeadCuffs plugin;
    private final Logger logger;

    // cuffed player UUID -> captor player UUID
    private final Map<UUID, UUID> cuffedPlayers = new HashMap<>();

    // cuffed player UUID -> their BukkitRunnable task
    private final Map<UUID, BukkitRunnable> followTasks = new HashMap<>();

    // Max distance the prisoner can be from captor before being pulled
    public static final double LEASH_RADIUS = 4.0;

    // Distance at which prisoner gets emergency-teleported
    private static final double TELEPORT_DISTANCE = 10.0;

    public CuffManager(LeadCuffs plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Cuff a target player to a captor.
     */
    public void cuff(Player captor, Player target) {
        UUID captorId = captor.getUniqueId();
        UUID targetId = target.getUniqueId();

        cuffedPlayers.put(targetId, captorId);

        // Slow the prisoner down
        target.setSprinting(false);
        target.setWalkSpeed(0.08f);

        // Sound feedback
        target.getWorld().playSound(target.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.8f);
        captor.getWorld().playSound(captor.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1.0f, 0.8f);

        logger.info("[LeadCuffs] " + captor.getName() + " cuffed " + target.getName());

        // Repeating task — every 2 ticks: check distance, teleport if needed, spawn particles
        BukkitRunnable task = new BukkitRunnable() {
            private int tick = 0;

            @Override
            public void run() {
                tick++;

                Player prisoner = Bukkit.getPlayer(targetId);
                Player holder = Bukkit.getPlayer(captorId);

                if (prisoner == null || holder == null || !prisoner.isOnline() || !holder.isOnline()) {
                    releaseSafe(targetId);
                    this.cancel();
                    return;
                }

                // Different worlds — just teleport
                if (!prisoner.getWorld().equals(holder.getWorld())) {
                    prisoner.teleport(holder.getLocation());
                    return;
                }

                Location pLoc = prisoner.getLocation();
                Location hLoc = holder.getLocation();
                double dist = pLoc.distance(hLoc);

                // If too far — teleport to captor
                if (dist > TELEPORT_DISTANCE) {
                    Location dest = hLoc.clone();
                    // Move 1.5 blocks behind the holder
                    Vector back = hLoc.getDirection().normalize().multiply(-1.5);
                    dest.add(back);
                    dest.setY(hLoc.getY());
                    dest.setYaw(pLoc.getYaw());
                    dest.setPitch(pLoc.getPitch());
                    prisoner.teleport(dest);
                    return;
                }

                // If beyond leash radius — teleport closer
                if (dist > LEASH_RADIUS) {
                    // Calculate direction from prisoner to holder
                    Vector dir = hLoc.toVector().subtract(pLoc.toVector()).normalize();
                    // Place prisoner at leash radius distance from holder
                    double moveBy = dist - LEASH_RADIUS + 0.5;
                    Location dest = pLoc.clone().add(dir.multiply(moveBy));
                    dest.setYaw(pLoc.getYaw());
                    dest.setPitch(pLoc.getPitch());
                    prisoner.teleport(dest);
                }

                // Kill sprint
                if (prisoner.isSprinting()) {
                    prisoner.setSprinting(false);
                }

                // Particle chain every 6 ticks (~300ms)
                if (tick % 3 == 0) {
                    drawChain(pLoc, hLoc);
                }
            }
        };

        task.runTaskTimer(plugin, 1L, 2L);
        followTasks.put(targetId, task);
    }

    /**
     * Draw a particle chain between two locations.
     */
    private void drawChain(Location from, Location to) {
        Vector dir = to.toVector().subtract(from.toVector());
        double len = dir.length();
        if (len < 0.5) return;
        dir.normalize();

        for (double d = 0; d < len; d += 0.7) {
            Location point = from.clone().add(dir.clone().multiply(d)).add(0, 1.2, 0);
            from.getWorld().spawnParticle(Particle.CRIT, point, 1, 0.02, 0.02, 0.02, 0);
        }
    }

    /**
     * Safe release that doesn't cause issues when called from inside the task.
     */
    private void releaseSafe(UUID targetId) {
        cuffedPlayers.remove(targetId);
        followTasks.remove(targetId);

        Player prisoner = Bukkit.getPlayer(targetId);
        if (prisoner != null && prisoner.isOnline()) {
            prisoner.setWalkSpeed(0.2f);
            prisoner.getWorld().playSound(prisoner.getLocation(), Sound.BLOCK_CHAIN_BREAK, 1.0f, 1.2f);
            prisoner.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, prisoner.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0);
        }

        logger.info("[LeadCuffs] Released prisoner " + targetId);
    }

    /**
     * Release a cuffed player by their UUID (called externally).
     */
    public void release(UUID targetId) {
        if (!cuffedPlayers.containsKey(targetId)) return;

        // Cancel the task
        BukkitRunnable task = followTasks.remove(targetId);
        if (task != null) {
            try {
                task.cancel();
            } catch (IllegalStateException ignored) {
                // Task might not be scheduled yet
            }
        }

        cuffedPlayers.remove(targetId);

        Player prisoner = Bukkit.getPlayer(targetId);
        if (prisoner != null && prisoner.isOnline()) {
            prisoner.setWalkSpeed(0.2f);
            prisoner.getWorld().playSound(prisoner.getLocation(), Sound.BLOCK_CHAIN_BREAK, 1.0f, 1.2f);
            prisoner.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, prisoner.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0);
        }

        logger.info("[LeadCuffs] Released prisoner " + targetId);
    }

    /**
     * Release all cuffed players (used on plugin disable).
     */
    public void releaseAll() {
        for (UUID targetId : new HashMap<>(cuffedPlayers).keySet()) {
            release(targetId);
        }
    }

    public boolean isCuffed(UUID playerId) {
        return cuffedPlayers.containsKey(playerId);
    }

    public UUID getCaptor(UUID playerId) {
        return cuffedPlayers.get(playerId);
    }

    public Map<UUID, UUID> getCuffedPlayers() {
        return cuffedPlayers;
    }
}
