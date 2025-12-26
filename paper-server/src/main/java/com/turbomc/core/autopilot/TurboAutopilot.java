package com.turbomc.core.autopilot;

import com.turbomc.config.TurboConfig;

/**
 * TurboAutopilot: The central intelligence of TurboMC.
 * Dynamically adjusts server parameters based on hardware and real-time health.
 */
public class TurboAutopilot {

    private static TurboAutopilot instance;
    private final HardwareSentry sentry;
    private final HealthMonitor health;
    
    private int currentEffectiveRadius;
    private boolean throttleActive = false;

    private TurboAutopilot() {
        this.sentry = HardwareSentry.getInstance();
        this.health = HealthMonitor.getInstance();
        this.currentEffectiveRadius = TurboConfig.getRawHyperViewRadius();
        
        System.out.println("[TurboMC][Autopilot] Brain initialized. Orchestrating performance...");
    }

    public static TurboAutopilot getInstance() {
        if (instance == null) instance = new TurboAutopilot();
        return instance;
    }

    /**
     * Resets the singleton instance for testing purposes.
     */
    public static void resetInstance() {
        instance = null;
    }

    /**
     * Called periodically (e.g., every 5 seconds) to recalibrate settings.
     */
    public void tick() {
        if (!TurboConfig.getInstance().getBoolean("autopilot.enabled", true)) return;

        HealthMonitor.HealthSnapshot status = health.capture();
        
        // 1. Safety Check: Hard radius limit based on hardware grade
        int maxSafe = sentry.getMaxSafeRadius();
        int requested = TurboConfig.getRawHyperViewRadius();
        
        int targetRadius = Math.min(requested, maxSafe);

        // 2. Dynamic Throttling: Reduce radius if server is struggling
        if (status.isCritical()) {
            targetRadius = 8; // Emergency fallback
            if (!throttleActive) {
                System.err.println("[TurboMC][Autopilot] CRITICAL LOAD DETECTED. Throttling HyperView to 8 chunks.");
                throttleActive = true;
            }
        } else if (status.isStruggling()) {
            targetRadius = Math.max(16, targetRadius / 2);
            if (!throttleActive) {
                System.out.println("[TurboMC][Autopilot] MSPT high (>50ms). Reducing HyperView radius.");
                throttleActive = true;
            }
        } else if (status.isHealthy()) {
            if (throttleActive) {
                System.out.println("[TurboMC][Autopilot] Server health recovered. Restoring requested radius.");
                throttleActive = false;
            }
        }

        this.currentEffectiveRadius = targetRadius;
    }

    /**
     * Gets the current radius that should be used by systems.
     * Calculated every tick based on health and hardware.
     */
    public int getEffectiveHyperViewRadius() {
        return currentEffectiveRadius;
    }

    /**
     * Updates the requested base radius (from command or config).
     */
    public void setRequestedRadius(int newRadius) {
        // We update the config so it persists, but also force a re-tick logic
        // TurboConfig.getInstance().set("world.generation.hyperview-radius", newRadius);
        // For now, we just re-run tick to apply clamping immediately
        tick(); 
    }

    /**
     * Hardware-aware thread count for generation.
     */
    public int getIdealGenerationThreads() {
        return sentry.getRecommendedThreads();
    }
}
