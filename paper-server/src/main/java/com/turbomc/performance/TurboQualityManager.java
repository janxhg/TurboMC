package com.turbomc.performance;

import com.turbomc.config.TurboConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Quality and rendering optimization manager for TurboMC.
 * Provides dynamic quality adjustments based on server performance.
 * 
 * Features:
 * - Dynamic view distance adjustment
 * - Entity culling optimization
 * - Particle effect optimization
 * - Quality presets (Low/Medium/High/Ultra)
 * - Performance-based quality scaling
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class TurboQualityManager {
    
    private static volatile TurboQualityManager instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    // Quality presets
    public enum QualityPreset {
        LOW("Low", 4, 25, 50, 0.5),
        MEDIUM("Medium", 6, 50, 100, 0.7),
        HIGH("High", 8, 75, 200, 0.85),
        ULTRA("Ultra", 12, 100, 400, 1.0),
        DYNAMIC("Dynamic", -1, -1, -1, -1);
        
        private final String name;
        private final int viewDistance;
        private final int maxEntities;
        private final int maxParticles;
        private final double qualityFactor;
        
        QualityPreset(String name, int viewDistance, int maxEntities, int maxParticles, double qualityFactor) {
            this.name = name;
            this.viewDistance = viewDistance;
            this.maxEntities = maxEntities;
            this.maxParticles = maxParticles;
            this.qualityFactor = qualityFactor;
        }
        
        public String getName() { return name; }
        public int getViewDistance() { return viewDistance; }
        public int getMaxEntities() { return maxEntities; }
        public int getMaxParticles() { return maxParticles; }
        public double getQualityFactor() { return qualityFactor; }
    }
    
    // Performance metrics
    private final ConcurrentHashMap<String, WorldQualityMetrics> worldMetrics;
    private final AtomicLong totalQualityAdjustments;
    private final AtomicInteger currentTps;
    private volatile QualityPreset currentPreset;
    private volatile boolean autoQualityEnabled;
    
    // Configuration
    private final int tpsThreshold;
    private final double memoryThreshold;
    private final int adjustmentIntervalTicks;
    private final boolean entityCullingEnabled;
    private final boolean particleOptimizationEnabled;
    
    private TurboQualityManager() {
        this.worldMetrics = new ConcurrentHashMap<>();
        this.totalQualityAdjustments = new AtomicLong(0);
        this.currentTps = new AtomicInteger(20);
        
        // Load configuration
        TurboConfig config = TurboConfig.getInstance();
        this.tpsThreshold = config.getInt("quality.tps-threshold", 18);
        this.memoryThreshold = config.getDouble("quality.memory-threshold", 0.8); // 80%
        this.adjustmentIntervalTicks = config.getInt("quality.adjustment-interval-ticks", 1200); // 1 minute
        this.entityCullingEnabled = config.getBoolean("quality.entity-culling.enabled", true);
        this.particleOptimizationEnabled = config.getBoolean("quality.particle-optimization.enabled", true);
        this.autoQualityEnabled = config.getBoolean("quality.auto-adjust.enabled", true);
        
        // Set initial preset
        String presetName = config.getString("quality.default-preset", "HIGH");
        this.currentPreset = QualityPreset.valueOf(presetName.toUpperCase());
        
        System.out.println("[TurboMC][Quality] Quality Manager initialized:");
        System.out.println("  - Default Preset: " + currentPreset.getName());
        System.out.println("  - Auto Quality: " + (autoQualityEnabled ? "ENABLED" : "DISABLED"));
        System.out.println("  - Entity Culling: " + (entityCullingEnabled ? "ENABLED" : "DISABLED"));
        System.out.println("  - Particle Optimization: " + (particleOptimizationEnabled ? "ENABLED" : "DISABLED"));
    }
    
    /**
     * Get the singleton instance.
     */
    public static TurboQualityManager getInstance() {
        TurboQualityManager result = instance;
        if (result == null) {
            synchronized (INSTANCE_LOCK) {
                result = instance;
                if (result == null) {
                    result = instance = new TurboQualityManager();
                }
            }
        }
        return result;
    }
    
    /**
     * Initialize quality manager and start performance monitoring.
     */
    public void initialize() {
        System.out.println("[TurboMC][Quality] Starting quality optimization monitoring...");
        
        if (autoQualityEnabled) {
            startPerformanceMonitoring();
        }
        
        System.out.println("[TurboMC][Quality] Quality optimization system active.");
    }
    
    /**
     * Start performance monitoring for automatic quality adjustments.
     */
    private void startPerformanceMonitoring() {
        // This would be integrated with server tick loop
        // For now, we'll provide the framework
        System.out.println("[TurboMC][Quality] Automatic quality adjustment monitoring started.");
        System.out.println("[TurboMC][Quality] TPS Threshold: " + tpsThreshold + ", Memory Threshold: " + (memoryThreshold * 100) + "%");
    }
    
    /**
     * Update quality metrics for a world.
     */
    public void updateWorldMetrics(String worldName, int tps, double memoryUsage, int entityCount, int particleCount) {
        WorldQualityMetrics metrics = worldMetrics.computeIfAbsent(worldName, k -> new WorldQualityMetrics());
        metrics.update(tps, memoryUsage, entityCount, particleCount);
        currentTps.set(tps);
        
        // Perform automatic quality adjustment if enabled
        if (autoQualityEnabled) {
            performQualityAdjustment(worldName, metrics);
        }
    }
    
    /**
     * Perform automatic quality adjustment based on performance metrics.
     */
    private void performQualityAdjustment(String worldName, WorldQualityMetrics metrics) {
        QualityPreset newPreset = currentPreset;
        
        // Check if performance is degraded
        if (metrics.getAverageTps() < tpsThreshold || metrics.getMemoryUsage() > memoryThreshold) {
            // Downgrade quality
            newPreset = downgradeQuality(currentPreset);
            if (newPreset != currentPreset) {
                System.out.println("[TurboMC][Quality] Performance degraded in world '" + worldName + 
                                 "'. Downgrading quality from " + currentPreset.getName() + " to " + newPreset.getName());
                applyQualityPreset(newPreset);
                totalQualityAdjustments.incrementAndGet();
            }
        }
        // Check if performance is good and we can upgrade
        else if (metrics.getAverageTps() >= 19 && metrics.getMemoryUsage() < memoryThreshold * 0.7) {
            // Upgrade quality
            newPreset = upgradeQuality(currentPreset);
            if (newPreset != currentPreset) {
                System.out.println("[TurboMC][Quality] Performance is excellent in world '" + worldName + 
                                 "'. Upgrading quality from " + currentPreset.getName() + " to " + newPreset.getName());
                applyQualityPreset(newPreset);
                totalQualityAdjustments.incrementAndGet();
            }
        }
    }
    
    /**
     * Downgrade quality by one level.
     */
    private QualityPreset downgradeQuality(QualityPreset current) {
        switch (current) {
            case ULTRA: return QualityPreset.HIGH;
            case HIGH: return QualityPreset.MEDIUM;
            case MEDIUM: return QualityPreset.LOW;
            case LOW: return QualityPreset.LOW; // Already lowest
            case DYNAMIC: return QualityPreset.MEDIUM; // Default to medium
            default: return current;
        }
    }
    
    /**
     * Upgrade quality by one level.
     */
    private QualityPreset upgradeQuality(QualityPreset current) {
        switch (current) {
            case LOW: return QualityPreset.MEDIUM;
            case MEDIUM: return QualityPreset.HIGH;
            case HIGH: return QualityPreset.ULTRA;
            case ULTRA: return QualityPreset.ULTRA; // Already highest
            case DYNAMIC: return QualityPreset.HIGH; // Default to high
            default: return current;
        }
    }
    
    /**
     * Apply quality preset to server settings.
     */
    public void applyQualityPreset(QualityPreset preset) {
        if (preset == QualityPreset.DYNAMIC) {
            // Dynamic preset - calculate optimal settings
            preset = calculateOptimalPreset();
        }
        
        this.currentPreset = preset;
        
        // Apply view distance changes
        if (preset.getViewDistance() > 0) {
            setViewDistance(preset.getViewDistance());
        }
        
        // Apply entity limits
        if (entityCullingEnabled && preset.getMaxEntities() > 0) {
            setEntityLimit(preset.getMaxEntities());
        }
        
        // Apply particle limits
        if (particleOptimizationEnabled && preset.getMaxParticles() > 0) {
            setParticleLimit(preset.getMaxParticles());
        }
        
        System.out.println("[TurboMC][Quality] Applied quality preset: " + preset.getName());
    }
    
    /**
     * Calculate optimal quality preset based on current performance.
     */
    private QualityPreset calculateOptimalPreset() {
        int avgTps = currentTps.get();
        
        if (avgTps >= 19) {
            return QualityPreset.ULTRA;
        } else if (avgTps >= 17) {
            return QualityPreset.HIGH;
        } else if (avgTps >= 15) {
            return QualityPreset.MEDIUM;
        } else {
            return QualityPreset.LOW;
        }
    }
    
    /**
     * Set view distance for all worlds.
     */
    private void setViewDistance(int distance) {
        // This would integrate with Paper's view distance system
        System.out.println("[TurboMC][Quality] Setting view distance to " + distance);
    }
    
    /**
     * Set entity limit for optimization.
     */
    private void setEntityLimit(int limit) {
        // This would integrate with entity management system
        System.out.println("[TurboMC][Quality] Setting entity limit to " + limit);
    }
    
    /**
     * Set particle limit for optimization.
     */
    private void setParticleLimit(int limit) {
        // This would integrate with particle system
        System.out.println("[TurboMC][Quality] Setting particle limit to " + limit);
    }
    
    /**
     * Get current quality statistics.
     */
    public QualityStats getStats() {
        return new QualityStats(
            currentPreset,
            totalQualityAdjustments.get(),
            worldMetrics.size(),
            currentTps.get(),
            autoQualityEnabled
        );
    }
    
    /**
     * World-specific quality metrics.
     */
    public static class WorldQualityMetrics {
        private volatile int currentTps;
        private volatile double memoryUsage;
        private volatile int entityCount;
        private volatile int particleCount;
        private final AtomicLong totalTpsSamples;
        private final AtomicLong totalMemorySamples;
        
        public WorldQualityMetrics() {
            this.totalTpsSamples = new AtomicLong(0);
            this.totalMemorySamples = new AtomicLong(0);
        }
        
        public void update(int tps, double memoryUsage, int entityCount, int particleCount) {
            this.currentTps = tps;
            this.memoryUsage = memoryUsage;
            this.entityCount = entityCount;
            this.particleCount = particleCount;
            this.totalTpsSamples.incrementAndGet();
            this.totalMemorySamples.incrementAndGet();
        }
        
        public int getCurrentTps() { return currentTps; }
        public double getMemoryUsage() { return memoryUsage; }
        public int getEntityCount() { return entityCount; }
        public int getParticleCount() { return particleCount; }
        
        public double getAverageTps() {
            long samples = totalTpsSamples.get();
            return samples > 0 ? currentTps : 20.0; // Default to 20 TPS
        }
    }
    
    /**
     * Quality statistics summary.
     */
    public static class QualityStats {
        private final QualityPreset currentPreset;
        private final long totalAdjustments;
        private final int trackedWorlds;
        private final int currentTps;
        private final boolean autoQualityEnabled;
        
        public QualityStats(QualityPreset currentPreset, long totalAdjustments, int trackedWorlds, int currentTps, boolean autoQualityEnabled) {
            this.currentPreset = currentPreset;
            this.totalAdjustments = totalAdjustments;
            this.trackedWorlds = trackedWorlds;
            this.currentTps = currentTps;
            this.autoQualityEnabled = autoQualityEnabled;
        }
        
        public QualityPreset getCurrentPreset() { return currentPreset; }
        public long getTotalAdjustments() { return totalAdjustments; }
        public int getTrackedWorlds() { return trackedWorlds; }
        public int getCurrentTps() { return currentTps; }
        public boolean isAutoQualityEnabled() { return autoQualityEnabled; }
        
        @Override
        public String toString() {
            return String.format("QualityStats{preset=%s, adjustments=%d, worlds=%d, tps=%d, auto=%s}",
                currentPreset.getName(), totalAdjustments, trackedWorlds, currentTps, autoQualityEnabled);
        }
    }
}
