package com.turbomc.core.autopilot;

import com.turbomc.config.TurboConfig;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Turbo Hardware Profiler - Fase 5: Dynamic Configuration
 * 
 * Detecta capacidades del hardware y ajusta automáticamente la configuración
 * de TurboMC para optimizar rendimiento basado en recursos disponibles.
 * 
 * @author TurboMC
 * @version 2.3.8
 */
public class TurboHardwareProfiler {
    
    private static final Logger LOGGER = Logger.getLogger(TurboHardwareProfiler.class.getName());
    private static volatile TurboHardwareProfiler instance;
    
    // Hardware detection
    private final int availableProcessors;
    private final long totalMemory;
    private final long maxMemory;
    private final String osName;
    private final String osArch;
    
    // Dynamic metrics
    private final AtomicLong lastProfileTime = new AtomicLong(0);
    private volatile HardwareProfile currentProfile;
    private volatile boolean isLowEndSystem;
    private volatile boolean isHighEndSystem;
    
    // Performance thresholds
    private static final long LOW_MEMORY_THRESHOLD = 4L * 1024 * 1024 * 1024; // 4GB
    private static final long HIGH_MEMORY_THRESHOLD = 16L * 1024 * 1024 * 1024; // 16GB
    private static final int LOW_CPU_THRESHOLD = 4; // 4 cores
    private static final int HIGH_CPU_THRESHOLD = 12; // 12+ cores
    
    public static class HardwareProfile {
        public final int cpuCores;
        public final long totalMemoryMB;
        public final long maxMemoryMB;
        public final String osType;
        public final String architecture;
        public final boolean is64Bit;
        public final double systemLoadAverage;
        public final double memoryPressure;
        public final PerformanceTier tier;
        
        public HardwareProfile(int cpuCores, long totalMemoryMB, long maxMemoryMB, 
                             String osType, String architecture, boolean is64Bit,
                             double systemLoadAverage, double memoryPressure, PerformanceTier tier) {
            this.cpuCores = cpuCores;
            this.totalMemoryMB = totalMemoryMB;
            this.maxMemoryMB = maxMemoryMB;
            this.osType = osType;
            this.architecture = architecture;
            this.is64Bit = is64Bit;
            this.systemLoadAverage = systemLoadAverage;
            this.memoryPressure = memoryPressure;
            this.tier = tier;
        }
        
        @Override
        public String toString() {
            return String.format("HardwareProfile{cores=%d, memory=%dMB, tier=%s, load=%.2f, pressure=%.2f}",
                               cpuCores, totalMemoryMB, tier, systemLoadAverage, memoryPressure);
        }
    }
    
    public enum PerformanceTier {
        LOW_END("Low-end", 1),      // <4 cores, <4GB RAM
        MID_RANGE("Mid-range", 2), // 4-8 cores, 4-8GB RAM  
        HIGH_END("High-end", 3),    // 8-12 cores, 8-16GB RAM
        SERVER_GRADE("Server", 4); // 12+ cores, >16GB RAM
        
        public final String name;
        public final int level;
        
        PerformanceTier(String name, int level) {
            this.name = name;
            this.level = level;
        }
    }
    
    private TurboHardwareProfiler() {
        // Get system information
        this.availableProcessors = Runtime.getRuntime().availableProcessors();
        this.totalMemory = Runtime.getRuntime().totalMemory();
        this.maxMemory = Runtime.getRuntime().maxMemory();
        this.osName = System.getProperty("os.name", "unknown").toLowerCase();
        this.osArch = System.getProperty("os.arch", "unknown").toLowerCase();
        
        // Initial profile
        this.currentProfile = generateProfile();
        this.isLowEndSystem = determineLowEndSystem();
        this.isHighEndSystem = determineHighEndSystem();
        
        LOGGER.info("[TurboMC][HardwareProfiler] Initialized: " + currentProfile);
    }
    
    public static TurboHardwareProfiler getInstance() {
        if (instance == null) {
            synchronized (TurboHardwareProfiler.class) {
                if (instance == null) {
                    instance = new TurboHardwareProfiler();
                }
            }
        }
        return instance;
    }
    
    /**
     * Generate current hardware profile with real-time metrics
     */
    public HardwareProfile generateProfile() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        
        // Get current memory usage
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        double memoryPressure = (double) heapUsage.getUsed() / heapUsage.getMax();
        
        // Get system load average (may return -1 on Windows)
        double systemLoadAverage = osBean.getSystemLoadAverage();
        if (systemLoadAverage < 0) {
            // Windows fallback: estimate based on available processors
            systemLoadAverage = availableProcessors > 0 ? 1.0 : 0.0;
        }
        
        // Determine performance tier
        PerformanceTier tier = determinePerformanceTier(availableProcessors, maxMemory);
        
        HardwareProfile profile = new HardwareProfile(
            availableProcessors,
            totalMemory / 1024 / 1024, // Convert to MB
            maxMemory / 1024 / 1024,   // Convert to MB
            osName,
            osArch,
            osArch.contains("64"),
            systemLoadAverage,
            memoryPressure,
            tier
        );
        
        lastProfileTime.set(System.currentTimeMillis());
        return profile;
    }
    
    private PerformanceTier determinePerformanceTier(int cores, long memory) {
        long memoryGB = memory / 1024 / 1024 / 1024;
        
        if (cores < LOW_CPU_THRESHOLD || memoryGB < 4) {
            return PerformanceTier.LOW_END;
        } else if (cores < 8 || memoryGB < 8) {
            return PerformanceTier.MID_RANGE;
        } else if (cores < HIGH_CPU_THRESHOLD || memoryGB < 16) {
            return PerformanceTier.HIGH_END;
        } else {
            return PerformanceTier.SERVER_GRADE;
        }
    }
    
    private boolean determineLowEndSystem() {
        return currentProfile.tier == PerformanceTier.LOW_END;
    }
    
    private boolean determineHighEndSystem() {
        return currentProfile.tier == PerformanceTier.HIGH_END || 
               currentProfile.tier == PerformanceTier.SERVER_GRADE;
    }
    
    /**
     * Get optimal thread count based on hardware profile
     */
    public int getOptimalThreadCount(double multiplier) {
        int baseThreads = availableProcessors;
        
        // Adjust based on system tier
        switch (currentProfile.tier) {
            case LOW_END:
                baseThreads = Math.max(2, baseThreads / 2);
                break;
            case MID_RANGE:
                baseThreads = Math.max(4, baseThreads);
                break;
            case HIGH_END:
                baseThreads = Math.max(6, baseThreads);
                break;
            case SERVER_GRADE:
                baseThreads = Math.max(8, baseThreads);
                break;
        }
        
        // Apply multiplier and clamp
        int result = (int) (baseThreads * multiplier);
        return Math.max(1, Math.min(result, availableProcessors * 2));
    }
    
    /**
     * Get optimal cache size based on available memory
     */
    public int getOptimalCacheSize(double memoryFraction) {
        long availableMemory = maxMemory - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        long cacheMemory = (long) (availableMemory * memoryFraction);
        
        // Convert to number of entries (assuming ~1KB per entry)
        int entries = (int) (cacheMemory / 1024);
        
        // Clamp to reasonable bounds
        return Math.max(1000, Math.min(entries, 100000));
    }
    
    /**
     * Get optimal queue size based on system capacity
     */
    public int getOptimalQueueSize() {
        switch (currentProfile.tier) {
            case LOW_END:
                return 1000;
            case MID_RANGE:
                return 5000;
            case HIGH_END:
                return 10000;
            case SERVER_GRADE:
                return 20000;
            default:
                return 5000;
        }
    }
    
    /**
     * Check if system is under memory pressure
     */
    public boolean isUnderMemoryPressure() {
        return currentProfile.memoryPressure > 0.8;
    }
    
    /**
     * Check if system is under CPU pressure
     */
    public boolean isUnderCpuPressure() {
        return currentProfile.systemLoadAverage > availableProcessors * 0.8;
    }
    
    /**
     * Get recommended configuration for current hardware
     */
    public String getRecommendedConfig() {
        StringBuilder config = new StringBuilder();
        config.append("# Auto-generated configuration for ").append(currentProfile.tier.name).append(" systems\n");
        config.append("# CPU: ").append(availableProcessors).append(" cores, Memory: ").append(currentProfile.totalMemoryMB).append("MB\n\n");
        
        // Thread pool configurations
        config.append("[storage.batch]\n");
        config.append("global-load-threads = ").append(getOptimalThreadCount(0.5)).append("\n");
        config.append("global-write-threads = ").append(getOptimalThreadCount(0.25)).append("\n");
        config.append("global-compression-threads = ").append(getOptimalThreadCount(0.3)).append("\n");
        config.append("global-decompression-threads = ").append(getOptimalThreadCount(0.4)).append("\n\n");
        
        // Cache configurations
        config.append("[storage.cache]\n");
        config.append("max-entries = ").append(getOptimalCacheSize(0.1)).append("\n");
        config.append("memory-limit-mb = ").append((int)(currentProfile.totalMemoryMB * 0.05)).append("\n\n");
        
        // Unified queue
        config.append("[storage.unified]\n");
        config.append("max-queue-size = ").append(getOptimalQueueSize()).append("\n");
        config.append("max-concurrent-tasks = ").append(getOptimalThreadCount(1.0)).append("\n\n");
        
        // World generation
        config.append("[world.generation]\n");
        config.append("generation-threads = ").append(getOptimalThreadCount(0.6)).append("\n");
        config.append("max-concurrent-generations = ").append(getOptimalThreadCount(0.8)).append("\n");
        
        return config.toString();
    }
    
    // Getters
    public HardwareProfile getCurrentProfile() {
        return currentProfile;
    }
    
    public boolean isLowEndSystem() {
        return isLowEndSystem;
    }
    
    public boolean isHighEndSystem() {
        return isHighEndSystem;
    }
    
    public int getAvailableProcessors() {
        return availableProcessors;
    }
    
    public long getMaxMemory() {
        return maxMemory;
    }
    
    /**
     * Refresh profile with current metrics
     */
    public void refreshProfile() {
        this.currentProfile = generateProfile();
        this.isLowEndSystem = determineLowEndSystem();
        this.isHighEndSystem = determineHighEndSystem();
    }
}
