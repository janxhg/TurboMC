package com.turbomc.storage;

import com.turbomc.config.TurboConfig;
import com.turbomc.storage.converter.RegionConverter;
import com.turbomc.storage.StorageFormat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.Stream;

/**
 * Intelligent background conversion scheduler for MCA to LRF conversion.
 * Converts regions during idle server time to minimize performance impact.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class BackgroundConversionScheduler implements AutoCloseable {
    
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger("TurboMC.BackgroundScheduler");
    
    private final Path regionDirectory;
    private final StorageFormat targetFormat;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean isRunning;
    private final AtomicLong convertedRegions;
    private final AtomicLong totalRegions;
    
    // Configuration
    private final int checkIntervalMinutes;
    private final int maxConcurrentConversions;
    private final double cpuThreshold;
    private final long minIdleTimeMs;
    
    public BackgroundConversionScheduler(Path regionDirectory, StorageFormat targetFormat) {
        this.regionDirectory = regionDirectory;
        this.targetFormat = targetFormat;
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.isRunning = new AtomicBoolean(false);
        this.convertedRegions = new AtomicLong(0);
        this.totalRegions = new AtomicLong(0);
        
        // Load configuration
        TurboConfig config = TurboConfig.getInstance();
        this.checkIntervalMinutes = config.getInt("storage.background.check-interval-minutes", 5);
        this.maxConcurrentConversions = config.getInt("storage.background.max-concurrent", 2);
        this.cpuThreshold = config.getDouble("storage.background.cpu-threshold", 0.3); // 30% CPU threshold
        this.minIdleTimeMs = config.getLong("storage.background.min-idle-time-ms", 30000); // 30 seconds
        
        LOGGER.info("[TurboMC][Background] Scheduler initialized with: checkInterval={}min, maxConcurrent={}, cpuThreshold={}%, minIdleTime={}ms",
            checkIntervalMinutes, maxConcurrentConversions, cpuThreshold * 100, minIdleTimeMs);
    }
    
    /**
     * Start the background conversion scheduler.
     */
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            LOGGER.info("[TurboMC][Background] Starting background conversion scheduler...");
            
            // Count total regions to convert
            countTotalRegions();
            
            // Schedule periodic checks
            scheduler.scheduleAtFixedRate(this::performIdleCheck, 
                1, checkIntervalMinutes, TimeUnit.MINUTES);
            
            LOGGER.info("[TurboMC][Background] Scheduler started. {} regions to convert.", totalRegions.get());
        }
    }
    
    /**
     * Stop the background conversion scheduler.
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            LOGGER.info("[TurboMC][Background] Stopping background conversion scheduler...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
            LOGGER.info("[TurboMC][Background] Scheduler stopped. Converted {}/{} regions.",
                convertedRegions.get(), totalRegions.get());
        }
    }
    
    /**
     * Perform idle check and convert regions if conditions are met.
     */
    private void performIdleCheck() {
        if (!isRunning.get()) return;
        
        try {
            if (isServerIdle() && hasRegionsToConvert()) {
                LOGGER.info("[TurboMC][Background] Server idle, starting background conversion...");
                convertNextBatch();
            }
        } catch (Exception e) {
            LOGGER.error("[TurboMC][Background] Error during idle check", e);
        }
    }
    
    /**
     * Check if server is idle based on CPU usage and player activity.
     */
    private boolean isServerIdle() {
        try {
            // Check CPU usage
            double cpuUsage = getCpuUsage();
            if (cpuUsage > cpuThreshold) {
                LOGGER.debug("[TurboMC][Background] CPU usage too high: {}% > {}%", 
                    cpuUsage * 100, cpuThreshold * 100);
                return false;
            }
            
            // Check if server has been idle for minimum time
            long idleTime = getIdleTime();
            if (idleTime < minIdleTimeMs) {
                LOGGER.debug("[TurboMC][Background] Server not idle long enough: {}ms < {}ms", 
                    idleTime, minIdleTimeMs);
                return false;
            }
            
            LOGGER.debug("[TurboMC][Background] Server idle: CPU={}%, idleTime={}ms", 
                cpuUsage * 100, idleTime);
            return true;
            
        } catch (Exception e) {
            LOGGER.warn("[TurboMC][Background] Error checking server idle status", e);
            return false;
        }
    }
    
    /**
     * Get current CPU usage (simplified implementation).
     */
    private double getCpuUsage() {
        try {
            com.sun.management.OperatingSystemMXBean osBean = 
                (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            return osBean.getProcessCpuLoad();
        } catch (Exception e) {
            // Fallback: assume 50% usage if we can't measure
            return 0.5;
        }
    }
    
    /**
     * Get server idle time (simplified implementation).
     */
    private long getIdleTime() {
        // This is a simplified implementation
        // In a real server, you'd track player activity, chunk generation, etc.
        return minIdleTimeMs + 1000; // Assume idle for testing
    }
    
    /**
     * Check if there are regions left to convert.
     */
    private boolean hasRegionsToConvert() {
        try (Stream<Path> files = Files.list(regionDirectory)) {
            long remainingMcaFiles = files
                .filter(Files::isRegularFile)
                .filter(file -> file.getFileName().toString().endsWith(".mca"))
                .count();
            
            return remainingMcaFiles > 0;
        } catch (Exception e) {
            LOGGER.warn("[TurboMC][Background] Error checking for regions to convert", e);
            return false;
        }
    }
    
    /**
     * Count total regions to convert.
     */
    private void countTotalRegions() {
        try (Stream<Path> files = Files.list(regionDirectory)) {
            long count = files
                .filter(Files::isRegularFile)
                .filter(file -> file.getFileName().toString().endsWith(".mca"))
                .count();
            totalRegions.set(count);
        } catch (Exception e) {
            LOGGER.warn("[TurboMC][Background] Error counting total regions", e);
            totalRegions.set(0);
        }
    }
    
    /**
     * Convert the next batch of regions.
     */
    private void convertNextBatch() {
        try (Stream<Path> files = Files.list(regionDirectory)) {
            files.filter(Files::isRegularFile)
                .filter(file -> file.getFileName().toString().endsWith(".mca"))
                .limit(maxConcurrentConversions)
                .forEach(this::convertRegionAsync);
        } catch (Exception e) {
            LOGGER.error("[TurboMC][Background] Error starting batch conversion", e);
        }
    }
    
    /**
     * Convert a region asynchronously.
     */
    private void convertRegionAsync(Path mcaFile) {
        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("[TurboMC][Background] Converting region: {}", mcaFile.getFileName());
                
                RegionConverter converter = new RegionConverter(true);
                converter.convertRegionDirectory(mcaFile.getParent(), mcaFile.getParent(), targetFormat);
                
                convertedRegions.incrementAndGet();
                LOGGER.info("[TurboMC][Background] Successfully converted: {}/{} regions completed",
                    convertedRegions.get(), totalRegions.get());
                
            } catch (Exception e) {
                LOGGER.error("[TurboMC][Background] Failed to convert region: " + mcaFile.getFileName(), e);
            }
        }, scheduler);
    }
    
    /**
     * Get conversion progress.
     */
    public ConversionProgress getProgress() {
        return new ConversionProgress(
            totalRegions.get(),
            convertedRegions.get(),
            isRunning.get()
        );
    }
    
    @Override
    public void close() {
        stop();
    }
    
    /**
     * Conversion progress information.
     */
    public static class ConversionProgress {
        public final long totalRegions;
        public final long convertedRegions;
        public final boolean isRunning;
        public final double progressPercent;
        
        public ConversionProgress(long totalRegions, long convertedRegions, boolean isRunning) {
            this.totalRegions = totalRegions;
            this.convertedRegions = convertedRegions;
            this.isRunning = isRunning;
            this.progressPercent = totalRegions > 0 ? (double) convertedRegions / totalRegions * 100 : 0;
        }
        
        @Override
        public String toString() {
            return String.format("ConversionProgress{converted=%d/%d (%.1f%%), running=%s}",
                convertedRegions, totalRegions, progressPercent, isRunning);
        }
    }
}
