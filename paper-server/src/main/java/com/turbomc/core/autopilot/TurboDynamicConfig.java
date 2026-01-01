package com.turbomc.core.autopilot;

import com.turbomc.config.TurboConfig;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Turbo Dynamic Configuration - Fase 5: Dynamic Configuration
 * 
 * Sistema de configuración dinámica que ajusta automáticamente los parámetros
 * de TurboMC basado en las capacidades del hardware y la carga actual del sistema.
 * 
 * @author TurboMC
 * @version 2.3.8
 */
public class TurboDynamicConfig {
    
    private static final Logger LOGGER = Logger.getLogger(TurboDynamicConfig.class.getName());
    private static volatile TurboDynamicConfig instance;
    
    // Components
    private final TurboHardwareProfiler hardwareProfiler;
    private final TurboConfig config;
    private final ScheduledExecutorService scheduler;
    
    // Dynamic adjustment state
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicLong lastAdjustmentTime = new AtomicLong(0);
    private volatile AdjustmentMode currentMode = AdjustmentMode.ADAPTIVE;
    
    // Performance thresholds
    private static final double MEMORY_PRESSURE_HIGH = 0.85;
    private static final double MEMORY_PRESSURE_LOW = 0.4;
    private static final double CPU_PRESSURE_HIGH = 0.8;
    private static final double CPU_PRESSURE_LOW = 0.3;
    
    // Adjustment intervals
    private static final long ADJUSTMENT_INTERVAL_SECONDS = 30; // Every 30 seconds
    private static final long PROFILE_REFRESH_INTERVAL_SECONDS = 60; // Every minute
    
    public enum AdjustmentMode {
        CONSERVATIVE("Conservative", 0.5),    // Low resource usage
        BALANCED("Balanced", 1.0),           // Default balanced mode
        AGGRESSIVE("Aggressive", 1.5),       // Maximum performance
        ADAPTIVE("Adaptive", 1.0);           // Auto-adjust based on load
        
        public final String name;
        public final double multiplier;
        
        AdjustmentMode(String name, double multiplier) {
            this.name = name;
            this.multiplier = multiplier;
        }
    }
    
    public enum ResourceType {
        THREAD_POOLS("Thread Pools"),
        CACHE_SIZES("Cache Sizes"),
        QUEUE_LIMITS("Queue Limits"),
        GENERATION("World Generation"),
        PREFETCHING("Chunk Prefetching");
        
        public final String name;
        
        ResourceType(String name) {
            this.name = name;
        }
    }
    
    private TurboDynamicConfig() {
        this.hardwareProfiler = TurboHardwareProfiler.getInstance();
        this.config = TurboConfig.getInstance();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TurboMC-DynamicConfig");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        
        // Start dynamic adjustment
        startDynamicAdjustment();
        
        LOGGER.info("[TurboMC][DynamicConfig] Initialized with mode: " + currentMode.name);
    }
    
    public static TurboDynamicConfig getInstance() {
        if (instance == null) {
            synchronized (TurboDynamicConfig.class) {
                if (instance == null) {
                    instance = new TurboDynamicConfig();
                }
            }
        }
        return instance;
    }
    
    /**
     * Start the dynamic adjustment scheduler
     */
    private void startDynamicAdjustment() {
        // Refresh hardware profile periodically
        scheduler.scheduleAtFixedRate(() -> {
            try {
                hardwareProfiler.refreshProfile();
            } catch (Exception e) {
                LOGGER.warning("[TurboMC][DynamicConfig] Error refreshing hardware profile: " + e.getMessage());
            }
        }, PROFILE_REFRESH_INTERVAL_SECONDS, PROFILE_REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        
        // Perform dynamic adjustments
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (enabled.get()) {
                    performDynamicAdjustment();
                }
            } catch (Exception e) {
                LOGGER.warning("[TurboMC][DynamicConfig] Error during dynamic adjustment: " + e.getMessage());
            }
        }, ADJUSTMENT_INTERVAL_SECONDS, ADJUSTMENT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }
    
    /**
     * Perform dynamic configuration adjustments
     */
    private void performDynamicAdjustment() {
        TurboHardwareProfiler.HardwareProfile profile = hardwareProfiler.getCurrentProfile();
        
        // Determine adjustment strategy based on system pressure
        if (shouldScaleDown(profile)) {
            adjustConfiguration(AdjustmentMode.CONSERVATIVE);
        } else if (shouldScaleUp(profile)) {
            adjustConfiguration(AdjustmentMode.AGGRESSIVE);
        } else {
            adjustConfiguration(AdjustmentMode.BALANCED);
        }
        
        lastAdjustmentTime.set(System.currentTimeMillis());
    }
    
    /**
     * Check if configuration should be scaled down
     */
    private boolean shouldScaleDown(TurboHardwareProfiler.HardwareProfile profile) {
        return profile.memoryPressure > MEMORY_PRESSURE_HIGH || 
               profile.systemLoadAverage > CPU_PRESSURE_HIGH ||
               hardwareProfiler.isUnderMemoryPressure() ||
               hardwareProfiler.isUnderCpuPressure();
    }
    
    /**
     * Check if configuration should be scaled up
     */
    private boolean shouldScaleUp(TurboHardwareProfiler.HardwareProfile profile) {
        return profile.memoryPressure < MEMORY_PRESSURE_LOW && 
               profile.systemLoadAverage < CPU_PRESSURE_LOW &&
               !hardwareProfiler.isUnderMemoryPressure() &&
               !hardwareProfiler.isUnderCpuPressure() &&
               hardwareProfiler.isHighEndSystem();
    }
    
    /**
     * Adjust configuration based on mode
     */
    private void adjustConfiguration(AdjustmentMode mode) {
        if (currentMode == mode) {
            return; // No change needed
        }
        
        LOGGER.info("[TurboMC][DynamicConfig] Adjusting configuration to " + mode.name + " mode");
        
        switch (mode) {
            case CONSERVATIVE:
                applyConservativeSettings();
                break;
            case BALANCED:
                applyBalancedSettings();
                break;
            case AGGRESSIVE:
                applyAggressiveSettings();
                break;
            case ADAPTIVE:
                applyAdaptiveSettings();
                break;
        }
        
        currentMode = mode;
    }
    
    /**
     * Apply conservative settings for low-resource scenarios
     */
    private void applyConservativeSettings() {
        // Reduce thread pools
        adjustThreadPoolSizes(0.5);
        
        // Reduce cache sizes
        adjustCacheSizes(0.3);
        
        // Reduce queue limits
        adjustQueueLimits(0.5);
        
        // Reduce generation threads
        adjustGenerationSettings(0.4);
        
        // Disable or reduce prefetching
        adjustPrefetchingSettings(0.2);
    }
    
    /**
     * Apply balanced settings for normal operation
     */
    private void applyBalancedSettings() {
        // Normal thread pools
        adjustThreadPoolSizes(1.0);
        
        // Normal cache sizes
        adjustCacheSizes(1.0);
        
        // Normal queue limits
        adjustQueueLimits(1.0);
        
        // Normal generation
        adjustGenerationSettings(1.0);
        
        // Normal prefetching
        adjustPrefetchingSettings(1.0);
    }
    
    /**
     * Apply aggressive settings for high-performance scenarios
     */
    private void applyAggressiveSettings() {
        // Increase thread pools
        adjustThreadPoolSizes(1.5);
        
        // Increase cache sizes
        adjustCacheSizes(1.5);
        
        // Increase queue limits
        adjustQueueLimits(1.5);
        
        // Increase generation
        adjustGenerationSettings(1.5);
        
        // Increase prefetching
        adjustPrefetchingSettings(1.5);
    }
    
    /**
     * Apply adaptive settings based on current hardware profile
     */
    private void applyAdaptiveSettings() {
        TurboHardwareProfiler.HardwareProfile profile = hardwareProfiler.getCurrentProfile();
        
        double multiplier = 1.0;
        switch (profile.tier) {
            case LOW_END:
                multiplier = 0.5;
                break;
            case MID_RANGE:
                multiplier = 1.0;
                break;
            case HIGH_END:
                multiplier = 1.3;
                break;
            case SERVER_GRADE:
                multiplier = 1.5;
                break;
        }
        
        adjustThreadPoolSizes(multiplier);
        adjustCacheSizes(multiplier);
        adjustQueueLimits(multiplier);
        adjustGenerationSettings(multiplier);
        adjustPrefetchingSettings(multiplier);
    }
    
    /**
     * Adjust thread pool sizes
     */
    private void adjustThreadPoolSizes(double multiplier) {
        int baseThreads = hardwareProfiler.getOptimalThreadCount(1.0);
        
        int loadThreads = (int)(baseThreads * 0.5 * multiplier);
        int writeThreads = (int)(baseThreads * 0.25 * multiplier);
        int compressionThreads = (int)(baseThreads * 0.3 * multiplier);
        int decompressionThreads = (int)(baseThreads * 0.4 * multiplier);

        // v2.3.9: Apply actual system changes
        com.turbomc.storage.optimization.TurboStorageManager.getInstance()
            .updateExecutors(
                Math.max(2, loadThreads), 
                Math.max(1, writeThreads), 
                Math.max(1, compressionThreads), 
                Math.max(2, decompressionThreads)
            );
            
        LOGGER.info("[TurboMC][DynamicConfig] Applied thread pool adjustments (multiplier: " + multiplier + ")");
    }
    
    /**
     * Adjust cache sizes
     */
    private void adjustCacheSizes(double multiplier) {
        int baseCacheSize = hardwareProfiler.getOptimalCacheSize(0.1);
        int adjustedSize = (int)(baseCacheSize * multiplier);
        
        LOGGER.info("[TurboMC][DynamicConfig] Recommended cache size: " + adjustedSize + " entries");
    }
    
    /**
     * Adjust queue limits
     */
    private void adjustQueueLimits(double multiplier) {
        int baseQueueSize = hardwareProfiler.getOptimalQueueSize();
        int adjustedSize = (int)(baseQueueSize * multiplier);
        
        LOGGER.info("[TurboMC][DynamicConfig] Recommended queue size: " + adjustedSize);
    }
    
    /**
     * Adjust generation settings
     */
    private void adjustGenerationSettings(double multiplier) {
        int baseThreads = hardwareProfiler.getOptimalThreadCount(0.6);
        int adjustedThreads = (int)(baseThreads * multiplier);
        
        LOGGER.info("[TurboMC][DynamicConfig] Recommended generation threads: " + adjustedThreads);
    }
    
    /**
     * Adjust prefetching settings
     */
    private void adjustPrefetchingSettings(double multiplier) {
        int baseRadius = 32; // Default HyperView radius
        int adjustedRadius = (int)(baseRadius * multiplier);
        
        LOGGER.info("[TurboMC][DynamicConfig] Recommended HyperView radius: " + adjustedRadius);
    }
    
    /**
     * Generate optimized configuration for current hardware
     */
    public String generateOptimizedConfig() {
        return hardwareProfiler.getRecommendedConfig();
    }
    
    /**
     * Apply optimized configuration to current config
     */
    public void applyOptimizedConfig() {
        String optimizedConfig = generateOptimizedConfig();
        LOGGER.info("[TurboMC][DynamicConfig] Generated optimized configuration:\n" + optimizedConfig);
        
        // In a real implementation, this would update the actual configuration
        // For now, we just log it for manual application
    }
    
    /**
     * Get current adjustment mode
     */
    public AdjustmentMode getCurrentMode() {
        return currentMode;
    }
    
    /**
     * Set adjustment mode manually
     */
    public void setMode(AdjustmentMode mode) {
        if (enabled.get()) {
            adjustConfiguration(mode);
        }
    }
    
    /**
     * Enable/disable dynamic configuration
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        LOGGER.info("[TurboMC][DynamicConfig] " + (enabled ? "Enabled" : "Disabled"));
    }
    
    /**
     * Check if dynamic configuration is enabled
     */
    public boolean isEnabled() {
        return enabled.get();
    }
    
    /**
     * Get last adjustment time
     */
    public long getLastAdjustmentTime() {
        return lastAdjustmentTime.get();
    }
    
    /**
     * Force immediate adjustment
     */
    public void forceAdjustment() {
        if (enabled.get()) {
            performDynamicAdjustment();
        }
    }
    
    /**
     * Get current system status
     */
    public String getSystemStatus() {
        TurboHardwareProfiler.HardwareProfile profile = hardwareProfiler.getCurrentProfile();
        
        StringBuilder status = new StringBuilder();
        status.append("=== TurboMC Dynamic Configuration Status ===\n");
        status.append("Mode: ").append(currentMode.name).append("\n");
        status.append("Hardware: ").append(profile.toString()).append("\n");
        status.append("Memory Pressure: ").append(String.format("%.2f%%", profile.memoryPressure * 100)).append("\n");
        status.append("CPU Load: ").append(String.format("%.2f", profile.systemLoadAverage)).append("\n");
        status.append("Under Memory Pressure: ").append(hardwareProfiler.isUnderMemoryPressure()).append("\n");
        status.append("Under CPU Pressure: ").append(hardwareProfiler.isUnderCpuPressure()).append("\n");
        status.append("Last Adjustment: ").append(new java.util.Date(lastAdjustmentTime.get())).append("\n");
        
        return status.toString();
    }
    
    /**
     * Shutdown the dynamic configuration system
     */
    public void shutdown() {
        LOGGER.info("[TurboMC][DynamicConfig] Shutting down...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
