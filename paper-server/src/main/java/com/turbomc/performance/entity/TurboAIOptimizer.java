package com.turbomc.performance.entity;

import com.turbomc.core.autopilot.HealthMonitor;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;

/**
 * Intelligent AI Throttling for TurboMC.
 * Dynamically scales entity AI frequency based on player proximity and server health.
 */
public class TurboAIOptimizer {

    private static final TurboAIOptimizer INSTANCE = new TurboAIOptimizer();

    public static TurboAIOptimizer getInstance() {
        return INSTANCE;
    }

    /**
     * Determines if the current AI tick should be skipped for the given mob.
     */
    public boolean shouldSkipAITick(Mob mob) {
        // Critical entities bypass throttling
        if (isThrottlingExempt(mob)) return false;

        HealthMonitor health = HealthMonitor.getInstance();
        double distSq = getDistanceToNearestPlayerSq(mob);
        
        // Define skip frequency based on health and distance
        int skipEvery;
        
        if (health.isOverloaded()) {
            // Aggressive throttling under heavy load
            if (distSq > 48 * 48) return true; // Freeze AI beyond 48 blocks
            if (distSq > 24 * 24) skipEvery = 10;
            else if (distSq > 12 * 12) skipEvery = 5;
            else skipEvery = 2;
        } else if (health.isUnderPressure()) {
            // Moderate throttling under pressure
            if (distSq > 64 * 64) skipEvery = 20;
            else if (distSq > 32 * 32) skipEvery = 10;
            else if (distSq > 16 * 16) skipEvery = 5;
            else skipEvery = 0;
        } else {
            // Minimal throttling under healthy conditions
            if (distSq > 64 * 64) skipEvery = 5;
            else if (distSq > 32 * 32) skipEvery = 2;
            else skipEvery = 0;
        }

        if (skipEvery <= 1) return false;
        return mob.tickCount % skipEvery != 0;
    }

    private boolean isThrottlingExempt(Mob mob) {
        // Bosses are always exempt
        if (mob instanceof EnderDragon || mob instanceof WitherBoss) return true;
        
        // Mobs currently targeting a player are exempt
        if (mob.getTarget() instanceof Player) return true;

        // Named mobs are exempt to avoid "frozen" appearance if players care about them
        if (mob.hasCustomName()) return true;

        // Mobs in vehicles are exempt from this specific check (handled elsewhere)
        if (mob.isPassenger()) return true;

        return false;
    }

    private double getDistanceToNearestPlayerSq(Mob mob) {
        Player nearest = mob.level().getNearestPlayer(mob, 128.0);
        if (nearest == null) return Double.MAX_VALUE;
        return mob.distanceToSqr(nearest);
    }
}
