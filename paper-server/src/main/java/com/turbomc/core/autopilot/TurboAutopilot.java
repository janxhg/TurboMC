package com.turbomc.core.autopilot;

import com.turbomc.config.TurboConfig;

/**
 * TurboAutopilot: The central intelligence of TurboMC.
 * Dynamically adjusts server parameters based on hardware and real-time health.
 * Enhanced for Fase 5: Dynamic Configuration integration.
 */
public class TurboAutopilot {

    private static TurboAutopilot instance;
    private final HardwareSentry sentry;
    private final HealthMonitor health;
    
    // Fase 5: Dynamic Configuration integration
    private final TurboHardwareProfiler hardwareProfiler;
    private final TurboDynamicConfig dynamicConfig;
    
    private int currentEffectiveRadius;
    private boolean throttleActive = false;

    private TurboAutopilot() {
        this.sentry = HardwareSentry.getInstance();
        this.health = HealthMonitor.getInstance();
        
        // Fase 5: Initialize dynamic configuration components
        this.hardwareProfiler = TurboHardwareProfiler.getInstance();
        this.dynamicConfig = TurboDynamicConfig.getInstance();
        
        this.currentEffectiveRadius = TurboConfig.getRawHyperViewRadius();
        
        System.out.println("[TurboMC][Autopilot] Brain initialized with Fase 5 Dynamic Configuration. Orchestrating performance...");
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
     * Enhanced for Fase 5: Includes dynamic configuration adjustments.
     */
    public void tick() {
        if (!TurboConfig.getInstance().getBoolean("autopilot.enabled", true)) return;

        HealthMonitor.HealthSnapshot status = health.capture();
        
        // Fase 5: Trigger dynamic configuration adjustments
        if (dynamicConfig.isEnabled()) {
            dynamicConfig.forceAdjustment();
        }
        
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
     * Enhanced for Fase 5: Uses integrated hardware profiler.
     */
    public int getIdealGenerationThreads() {
        return sentry.getRecommendedThreads();
    }
    
    // Fase 5: Dynamic Configuration methods
    
    /**
     * Get current dynamic configuration mode
     */
    public TurboDynamicConfig.AdjustmentMode getDynamicMode() {
        return dynamicConfig.getCurrentMode();
    }
    
    /**
     * Set dynamic configuration mode
     */
    public void setDynamicMode(TurboDynamicConfig.AdjustmentMode mode) {
        dynamicConfig.setMode(mode);
    }
    
    /**
     * Get hardware profile information
     */
    public TurboHardwareProfiler.HardwareProfile getHardwareProfile() {
        return hardwareProfiler.getCurrentProfile();
    }
    
    /**
     * Generate optimized configuration for current hardware
     */
    public String generateOptimizedConfig() {
        return hardwareProfiler.getRecommendedConfig();
    }
    
    /**
     * Get system status including dynamic configuration
     */
    public String getSystemStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== TurboMC Autopilot Status (Fase 5 Enhanced) ===\n");
        status.append("Dynamic Config: ").append(dynamicConfig.isEnabled() ? "Enabled" : "Disabled").append("\n");
        status.append("Current Mode: ").append(dynamicConfig.getCurrentMode().name).append("\n");
        status.append("Hardware Profile: ").append(hardwareProfiler.getCurrentProfile().toString()).append("\n");
        status.append("HyperView Radius: ").append(currentEffectiveRadius).append("\n");
        status.append("Throttle Active: ").append(throttleActive).append("\n");
        status.append("\n").append(dynamicConfig.getSystemStatus());
        
        return status.toString();
    }
    
    /**
     * Enable/disable dynamic configuration
     */
    public void setDynamicConfigEnabled(boolean enabled) {
        dynamicConfig.setEnabled(enabled);
    }
    
    /**
     * Force immediate dynamic adjustment
     */
    public void forceDynamicAdjustment() {
        dynamicConfig.forceAdjustment();
    }
}
