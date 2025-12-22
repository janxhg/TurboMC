package com.turbomc.storage;

import com.turbomc.config.TurboConfig;
import com.turbomc.storage.integrity.ChunkIntegrityValidator.ChecksumAlgorithm;
import com.turbomc.storage.batch.ChunkBatchLoader;
import com.turbomc.storage.batch.ChunkBatchSaver;
import com.turbomc.storage.mmap.MMapReadAheadEngine;
import com.turbomc.storage.integrity.ChunkIntegrityValidator;
import com.turbomc.storage.lrf.LRFChunkEntry;
import com.turbomc.storage.lrf.LRFConstants;
import com.turbomc.storage.lrf.LRFRegionReader;
import com.turbomc.storage.lrf.LRFRegionWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Central manager for all TurboMC storage operations.
 * Orchestrates batch loading/saving, memory-mapped I/O, and integrity validation.
 * 
 * This is the main entry point for all storage operations in TurboMC.
 * It manages the lifecycle of all storage components and provides a unified API.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class TurboStorageManager implements AutoCloseable {
    
    private static final Logger LOGGER = Logger.getLogger(TurboStorageManager.class.getName());
    private static volatile TurboStorageManager instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    // Component managers
    private final ConcurrentHashMap<Path, ChunkBatchLoader> batchLoaders;
    private final ConcurrentHashMap<Path, ChunkBatchSaver> batchSavers;
    private final ConcurrentHashMap<Path, MMapReadAheadEngine> readAheadEngines;
    private final ConcurrentHashMap<Path, ChunkIntegrityValidator> integrityValidators;
    
    // Configuration
    private final TurboConfig config;
    private final AtomicBoolean isInitialized;
    private final AtomicBoolean isClosed;
    
    // Feature flags
    private final boolean batchEnabled;
    private final boolean mmapEnabled;
    private final boolean integrityEnabled;
    
    private TurboStorageManager(TurboConfig config) {
        this.config = config;
        this.batchLoaders = new ConcurrentHashMap<>();
        this.batchSavers = new ConcurrentHashMap<>();
        this.readAheadEngines = new ConcurrentHashMap<>();
        this.integrityValidators = new ConcurrentHashMap<>();
        this.isInitialized = new AtomicBoolean(false);
        this.isClosed = new AtomicBoolean(false);
        
        // Load feature flags from configuration
        this.batchEnabled = config.getBoolean("storage.batch.enabled", true);
        this.mmapEnabled = config.getBoolean("storage.mmap.enabled", true);
        this.integrityEnabled = config.getBoolean("storage.integrity.enabled", true);
        
        System.out.println("[TurboMC][Storage] Storage Manager initialized:");
        System.out.println("  - Batch Operations: " + (batchEnabled ? "ENABLED" : "DISABLED"));
        System.out.println("  - Memory-Mapped I/O: " + (mmapEnabled ? "ENABLED" : "DISABLED"));
        System.out.println("  - Integrity Validation: " + (integrityEnabled ? "ENABLED" : "DISABLED"));
    }
    
    /**
     * Get the singleton instance of the storage manager.
     * 
     * @return Storage manager instance
     */
    public static TurboStorageManager getInstance() {
        TurboStorageManager result = instance;
        if (result == null) {
            synchronized (INSTANCE_LOCK) {
                result = instance;
                if (result == null) {
                    TurboConfig config = TurboConfig.getInstance();
                    result = instance = new TurboStorageManager(config);
                }
            }
        }
        return result;
    }
    
    /**
     * Initialize the storage manager with all components.
     * Should be called during server startup.
     */
    public void initialize() {
        if (isInitialized.compareAndSet(false, true)) {
            System.out.println("[TurboMC][Storage] Initializing storage components...");
            
            // No global initialization needed - components are created on-demand per region
            // This saves memory and resources for worlds that aren't actively used
            
            System.out.println("[TurboMC][Storage] Storage components initialized successfully.");
        }
    }
    
    /**
     * Load a chunk using all available optimizations.
     * This is the main entry point for chunk loading.
     * 
     * @param regionPath Path to the region file
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return CompletableFuture that completes with the chunk data
     */
    public CompletableFuture<LRFChunkEntry> loadChunk(Path regionPath, int chunkX, int chunkZ) {
        if (isClosed.get()) {
            throw new IllegalStateException("Storage manager is closed");
        }
        
        // Try memory-mapped read-ahead first (fastest)
        if (mmapEnabled) {
            MMapReadAheadEngine mmapEngine = getReadAheadEngine(regionPath);
            if (mmapEngine != null) {
                try {
                    byte[] data = mmapEngine.readChunk(chunkX, chunkZ);
                    if (data != null) {
                        LRFChunkEntry chunk = new LRFChunkEntry(chunkX, chunkZ, data);
                        
                        // Validate integrity if enabled
                        if (integrityEnabled) {
                            return validateChunk(regionPath, chunkX, chunkZ, data)
                                .thenApply(report -> {
                                    if (report.isCorrupted()) {
                                        System.err.println("[TurboMC][Storage] Chunk corruption detected: " + 
                                                         report.getMessage());
                                    }
                                    return chunk;
                                });
                        }
                        
                        return CompletableFuture.completedFuture(chunk);
                    }
                } catch (IOException e) {
                    System.err.println("[TurboMC][Storage] MMap read failed, falling back to batch loader: " + e.getMessage());
                }
            }
        }
        
        // Fallback to batch loader
        if (batchEnabled) {
            ChunkBatchLoader loader = getBatchLoader(regionPath);
            if (loader != null) {
                CompletableFuture<LRFChunkEntry> future = loader.loadChunk(chunkX, chunkZ);
                
                // Validate integrity if enabled
                if (integrityEnabled) {
                    return future.thenCompose(chunk -> {
                        if (chunk != null) {
                            return validateChunk(regionPath, chunkX, chunkZ, chunk.getData())
                                .thenApply(report -> {
                                    if (report.isCorrupted()) {
                                        System.err.println("[TurboMC][Storage] Chunk corruption detected: " + 
                                                         report.getMessage());
                                    }
                                    return chunk;
                                });
                        }
                        return CompletableFuture.completedFuture(null);
                    });
                }
                
                return future;
            }
        }
        
        // Final fallback to direct LRF reader
        return CompletableFuture.supplyAsync(() -> {
            try {
                LRFRegionReader reader = new LRFRegionReader(regionPath);
                LRFChunkEntry chunk = reader.readChunk(chunkX, chunkZ);
                reader.close();
                return chunk;
            } catch (IOException e) {
                System.err.println("[TurboMC][Storage] Failed to load chunk " + chunkX + "," + chunkZ + ": " + e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * Save a chunk using all available optimizations.
     * This is the main entry point for chunk saving.
     * 
     * @param regionPath Path to the region file
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param data Chunk data to save
     * @return CompletableFuture that completes when the chunk is saved
     */
    public CompletableFuture<Void> saveChunk(Path regionPath, int chunkX, int chunkZ, byte[] data) {
        if (isClosed.get()) {
            throw new IllegalStateException("Storage manager is closed");
        }
        
        LRFChunkEntry chunk = new LRFChunkEntry(chunkX, chunkZ, data);
        
        // Validate integrity before saving if enabled
        if (integrityEnabled) {
            ChunkIntegrityValidator validator = getIntegrityValidator(regionPath);
            if (validator != null) {
                return validator.validateChunk(chunkX, chunkZ, data)
                    .thenCompose(report -> {
                        if (report.isCorrupted()) {
                            System.err.println("[TurboMC][Storage] Not saving corrupted chunk: " + report.getMessage());
                            return CompletableFuture.completedFuture(null);
                        }
                        
                        // Proceed with saving
                        return saveChunkInternal(regionPath, chunk);
                    });
            }
        }
        
        return saveChunkInternal(regionPath, chunk);
    }
    
    /**
     * Internal chunk saving method.
     */
    private CompletableFuture<Void> saveChunkInternal(Path regionPath, LRFChunkEntry chunk) {
        if (batchEnabled) {
            ChunkBatchSaver saver = getBatchSaver(regionPath);
            if (saver != null) {
                return saver.saveChunk(chunk);
            }
        }
        
        // Fallback to direct LRF writer
        return CompletableFuture.runAsync(() -> {
            try {
                LRFRegionWriter writer = new LRFRegionWriter(regionPath);
                writer.addChunk(chunk);
                writer.flush();
                writer.close();
            } catch (IOException e) {
                System.err.println("[TurboMC][Storage] Failed to save chunk " + 
                                 chunk.getChunkX() + "," + chunk.getChunkZ() + ": " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * Load multiple chunks in parallel.
     * 
     * @param regionPath Path to the region file
     * @param chunkCoords List of chunk coordinate pairs [x, z]
     * @return CompletableFuture that completes with list of loaded chunks
     */
    public CompletableFuture<java.util.List<LRFChunkEntry>> loadChunks(Path regionPath, java.util.List<int[]> chunkCoords) {
        if (batchEnabled) {
            ChunkBatchLoader loader = getBatchLoader(regionPath);
            if (loader != null) {
                return loader.loadChunks(chunkCoords);
            }
        }
        
        // Fallback to individual loads
        java.util.List<CompletableFuture<LRFChunkEntry>> futures = new java.util.ArrayList<>();
        for (int[] coords : chunkCoords) {
            futures.add(loadChunk(regionPath, coords[0], coords[1]));
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                java.util.List<LRFChunkEntry> results = new java.util.ArrayList<>();
                for (CompletableFuture<LRFChunkEntry> future : futures) {
                    try {
                        LRFChunkEntry chunk = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                        if (chunk != null) {
                            results.add(chunk);
                        }
                    } catch (Exception e) {
                        LOGGER.log(java.util.logging.Level.WARNING, "Failed to load chunk during batch operation", e);
                        // Skip failed chunks but continue with others
                    }
                }
                return results;
            });
    }
    
    /**
     * Validate a chunk's integrity.
     */
    private CompletableFuture<ChunkIntegrityValidator.IntegrityReport> validateChunk(Path regionPath, int chunkX, int chunkZ, byte[] data) {
        if (integrityEnabled) {
            ChunkIntegrityValidator validator = getIntegrityValidator(regionPath);
            if (validator != null) {
                return validator.validateChunk(chunkX, chunkZ, data);
            }
        }
        
        // Return a valid report if integrity validation is disabled
        ChunkIntegrityValidator.IntegrityReport report = 
            new ChunkIntegrityValidator.IntegrityReport(chunkX, chunkZ, 
                ChunkIntegrityValidator.ValidationResult.VALID, 
                "Integrity validation disabled", data.length);
        return CompletableFuture.completedFuture(report);
    }
    
    /**
     * Get or create a batch loader for the specified region.
     */
    private ChunkBatchLoader getBatchLoader(Path regionPath) {
        if (!batchEnabled) return null;
        
        return batchLoaders.computeIfAbsent(regionPath, path -> {
            try {
                int loadThreads = config.getInt("storage.batch.load-threads", 4);
                int decompressionThreads = Math.max(1, loadThreads / 2);
                int batchSize = config.getInt("storage.batch.batch-size", 32);
                int maxConcurrentLoads = config.getInt("storage.batch.max-concurrent-loads", 64);
                
                return new ChunkBatchLoader(path, loadThreads, decompressionThreads, 
                                           batchSize, maxConcurrentLoads);
            } catch (IOException e) {
                System.err.println("[TurboMC][Storage] Failed to create batch loader for " + path + ": " + e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * Get or create a batch saver for the specified region.
     */
    private ChunkBatchSaver getBatchSaver(Path regionPath) {
        if (!batchEnabled) return null;
        
        return batchSavers.computeIfAbsent(regionPath, path -> {
            int saveThreads = config.getInt("storage.batch.save-threads", 2);
            int compressionThreads = Math.max(1, saveThreads / 2);
            int batchSize = config.getInt("storage.batch.batch-size", 32);
            int compressionType = LRFConstants.COMPRESSION_LZ4; // Default to LZ4
            
            return new ChunkBatchSaver(path, compressionType, compressionThreads, 
                                     saveThreads, batchSize);
        });
    }
    
    /**
     * Get or create a memory-mapped read-ahead engine for the specified region.
     */
    private MMapReadAheadEngine getReadAheadEngine(Path regionPath) {
        if (!mmapEnabled) return null;
        
        return readAheadEngines.computeIfAbsent(regionPath, path -> {
            try {
                int maxCacheSize = config.getInt("storage.mmap.max-cache-size", 512);
                int prefetchDistance = config.getInt("storage.mmap.prefetch-distance", 8);
                int prefetchBatchSize = config.getInt("storage.mmap.prefetch-batch-size", 32);
                long maxMemoryUsage = config.getLong("storage.mmap.max-memory-usage", 256) * 1024 * 1024; // MB to bytes
                
                boolean predictive = config.getBoolean("storage.mmap.predictive-enabled", true);
                int predictionScale = config.getInt("storage.mmap.prediction-scale", 6);
                
                return new MMapReadAheadEngine(path, maxCacheSize, prefetchDistance, 
                                             prefetchBatchSize, maxMemoryUsage, predictive, predictionScale);
            } catch (IOException e) {
                System.err.println("[TurboMC][Storage] Failed to create MMap engine for " + path + ": " + e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * Get or create an integrity validator for the specified region.
     */
    private ChunkIntegrityValidator getIntegrityValidator(Path regionPath) {
        if (!integrityEnabled) return null;
        
        return integrityValidators.computeIfAbsent(regionPath, path -> {
            String primaryAlgorithmName = config.getString("storage.integrity.primary-algorithm", "crc32c");
            String backupAlgorithmName = config.getString("storage.integrity.backup-algorithm", "sha256");
            boolean autoRepair = config.getBoolean("storage.integrity.auto-repair", true);
            int validationThreads = config.getInt("storage.integrity.validation-threads", 2);
            long validationInterval = config.getLong("storage.integrity.validation-interval", 300000);
            
            ChecksumAlgorithm primaryAlgorithm = parseChecksumAlgorithm(primaryAlgorithmName);
            ChecksumAlgorithm backupAlgorithm = parseChecksumAlgorithm(backupAlgorithmName);
            
            return new ChunkIntegrityValidator(path, primaryAlgorithm, backupAlgorithm, 
                                             autoRepair, validationThreads, validationInterval);
        });
    }
    
    /**
     * Parse checksum algorithm name.
     */
    private ChecksumAlgorithm parseChecksumAlgorithm(String name) {
        if (name == null || name.equalsIgnoreCase("null") || name.equalsIgnoreCase("none")) {
            return null;
        }
        
        try {
            return ChecksumAlgorithm.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("[TurboMC][Storage] Unknown checksum algorithm: " + name + ", using CRC32C");
            return ChecksumAlgorithm.CRC32C;
        }
    }
    
    /**
     * Get comprehensive statistics from all components.
     */
    public StorageManagerStats getStats() {
        int totalLoaded = 0, totalDecompressed = 0, totalCacheHits = 0, totalCacheMisses = 0;
        long totalLoadTime = 0, totalDecompressionTime = 0;
        
        for (ChunkBatchLoader loader : batchLoaders.values()) {
            ChunkBatchLoader.BatchLoaderStats stats = loader.getStats();
            totalLoaded += stats.getChunksLoaded();
            totalDecompressed += stats.getChunksDecompressed();
            totalCacheHits += stats.getCacheHits();
            totalCacheMisses += stats.getCacheMisses();
            totalLoadTime += stats.getTotalLoadTime();
            totalDecompressionTime += stats.getTotalDecompressionTime();
        }
        
        int totalValidated = 0, totalCorrupted = 0, totalRepaired = 0;
        long totalValidationTime = 0;
        int totalChecksumsStored = 0;
        long totalChecksumStorage = 0;
        
        for (ChunkIntegrityValidator validator : integrityValidators.values()) {
            ChunkIntegrityValidator.IntegrityStats stats = validator.getStats();
            totalValidated += stats.getChunksValidated();
            totalCorrupted += stats.getChunksCorrupted();
            totalRepaired += stats.getChunksRepaired();
            totalValidationTime += stats.getTotalValidationTime();
            totalChecksumsStored += stats.getChecksumsStored();
            totalChecksumStorage += stats.getChecksumStorageSize();
        }
        
        int mmapRegions = readAheadEngines.size();
        long mmapMemoryUsage = readAheadEngines.values().stream()
            .mapToLong(engine -> engine.getStats().getMemoryUsage())
            .sum();
        
        return new StorageManagerStats(
            batchLoaders.size(), batchSavers.size(), mmapRegions, integrityValidators.size(),
            totalLoaded, totalDecompressed, totalCacheHits, totalCacheMisses,
            totalLoadTime, totalDecompressionTime,
            totalValidated, totalCorrupted, totalRepaired, totalValidationTime,
            totalChecksumsStored, totalChecksumStorage,
            mmapMemoryUsage, batchEnabled, mmapEnabled, integrityEnabled
        );
    }
    
    /**
     * Close all storage components for a specific region.
     * Call this when a world is unloaded.
     */
    public void closeRegion(Path regionPath) {
        try {
            ChunkBatchLoader loader = batchLoaders.remove(regionPath);
            if (loader != null) {
                loader.close();
            }
            
            ChunkBatchSaver saver = batchSavers.remove(regionPath);
            if (saver != null) {
                saver.close();
            }
            
            MMapReadAheadEngine mmapEngine = readAheadEngines.remove(regionPath);
            if (mmapEngine != null) {
                mmapEngine.close();
            }
            
            ChunkIntegrityValidator validator = integrityValidators.remove(regionPath);
            if (validator != null) {
                validator.close();
            }
            
            System.out.println("[TurboMC][Storage] Closed storage components for: " + regionPath.getFileName());
        } catch (IOException e) {
            System.err.println("[TurboMC][Storage] Error closing region " + regionPath + ": " + e.getMessage());
        }
    }
    
    @Override
    public void close() throws IOException {
        if (isClosed.compareAndSet(false, true)) {
            System.out.println("[TurboMC][Storage] Shutting down storage manager...");
            
            // FIXED: Add exception handling for resource cleanup
            for (ChunkBatchLoader loader : batchLoaders.values()) {
                try {
                    loader.close();
                } catch (Exception e) {
                    System.err.println("[TurboMC][Storage] Error closing batch loader: " + e.getMessage());
                }
            }
            
            for (ChunkBatchSaver saver : batchSavers.values()) {
                try {
                    saver.close();
                } catch (Exception e) {
                    System.err.println("[TurboMC][Storage] Error closing batch saver: " + e.getMessage());
                }
            }
            
            for (MMapReadAheadEngine engine : readAheadEngines.values()) {
                try {
                    engine.close();
                } catch (Exception e) {
                    System.err.println("[TurboMC][Storage] Error closing read-ahead engine: " + e.getMessage());
                }
            }
            
            for (ChunkIntegrityValidator validator : integrityValidators.values()) {
                try {
                    validator.close();
                } catch (Exception e) {
                    System.err.println("[TurboMC][Storage] Error closing integrity validator: " + e.getMessage());
                }
            }
            
            // Clear collections
            batchLoaders.clear();
            batchSavers.clear();
            readAheadEngines.clear();
            integrityValidators.clear();
            
            System.out.println("[TurboMC][Storage] Final stats: " + getStats());
            System.out.println("[TurboMC][Storage] Storage manager shutdown complete.");
        }
    }
    
    /**
     * Comprehensive statistics for the storage manager.
     */
    public static class StorageManagerStats {
        private final int batchLoaders;
        private final int batchSavers;
        private final int mmapEngines;
        private final int integrityValidators;
        private final int totalLoaded;
        private final int totalDecompressed;
        private final int totalCacheHits;
        private final int totalCacheMisses;
        private final long totalLoadTime;
        private final long totalDecompressionTime;
        private final int totalValidated;
        private final int totalCorrupted;
        private final int totalRepaired;
        private final long totalValidationTime;
        private final int totalChecksumsStored;
        private final long totalChecksumStorage;
        private final long mmapMemoryUsage;
        private final boolean batchEnabled;
        private final boolean mmapEnabled;
        private final boolean integrityEnabled;
        
        StorageManagerStats(int batchLoaders, int batchSavers, int mmapEngines, int integrityValidators,
                          int totalLoaded, int totalDecompressed, int totalCacheHits, int totalCacheMisses,
                          long totalLoadTime, long totalDecompressionTime,
                          int totalValidated, int totalCorrupted, int totalRepaired, long totalValidationTime,
                          int totalChecksumsStored, long totalChecksumStorage,
                          long mmapMemoryUsage, boolean batchEnabled, boolean mmapEnabled, boolean integrityEnabled) {
            this.batchLoaders = batchLoaders;
            this.batchSavers = batchSavers;
            this.mmapEngines = mmapEngines;
            this.integrityValidators = integrityValidators;
            this.totalLoaded = totalLoaded;
            this.totalDecompressed = totalDecompressed;
            this.totalCacheHits = totalCacheHits;
            this.totalCacheMisses = totalCacheMisses;
            this.totalLoadTime = totalLoadTime;
            this.totalDecompressionTime = totalDecompressionTime;
            this.totalValidated = totalValidated;
            this.totalCorrupted = totalCorrupted;
            this.totalRepaired = totalRepaired;
            this.totalValidationTime = totalValidationTime;
            this.totalChecksumsStored = totalChecksumsStored;
            this.totalChecksumStorage = totalChecksumStorage;
            this.mmapMemoryUsage = mmapMemoryUsage;
            this.batchEnabled = batchEnabled;
            this.mmapEnabled = mmapEnabled;
            this.integrityEnabled = integrityEnabled;
        }
        
        // Default constructor for empty stats
        public StorageManagerStats() {
            this(0, 0, 0, 0, 0, 0, 0, 8, 0L, 0L, 0, 0, 0, 0L, 0, 0L, 0L, false, false, false);
        }
        
        // Getters...
        public int getBatchLoaders() { return batchLoaders; }
        public int getBatchSavers() { return batchSavers; }
        public int getMmapEngines() { return mmapEngines; }
        public int getIntegrityValidators() { return integrityValidators; }
        public int getTotalLoaded() { return totalLoaded; }
        public int getTotalDecompressed() { return totalDecompressed; }
        public int getTotalCacheHits() { return totalCacheHits; }
        public int getTotalCacheMisses() { return totalCacheMisses; }
        public long getTotalLoadTime() { return totalLoadTime; }
        public long getTotalDecompressionTime() { return totalDecompressionTime; }
        
        public double getAvgLoadTime() {
            return totalLoaded > 0 ? (double) totalLoadTime / totalLoaded / 1_000_000.0 : 0; // Convert nanoseconds to milliseconds
        }
        public int getTotalValidated() { return totalValidated; }
        public int getTotalCorrupted() { return totalCorrupted; }
        public int getTotalRepaired() { return totalRepaired; }
        public long getTotalValidationTime() { return totalValidationTime; }
        public int getTotalChecksumsStored() { return totalChecksumsStored; }
        public long getTotalChecksumStorage() { return totalChecksumStorage; }
        public long getMmapMemoryUsage() { return mmapMemoryUsage; }
        public boolean isBatchEnabled() { return batchEnabled; }
        public boolean isMmapEnabled() { return mmapEnabled; }
        public boolean isIntegrityEnabled() { return integrityEnabled; }
        
        public double getCacheHitRate() {
            int total = totalCacheHits + totalCacheMisses;
            return total > 0 ? (double) totalCacheHits / total * 100 : 0;
        }
        
        public double getCorruptionRate() {
            return totalValidated > 0 ? (double) totalCorrupted / totalValidated * 100 : 0;
        }
        
        @Override
        public String toString() {
            return String.format("StorageManagerStats{loaders=%d,savers=%d,mmap=%d,validators=%d," +
                               "loaded=%d,decompressed=%d,cache=%.1f%%,corruption=%.2f%%," +
                               "mmapMemory=%.1fMB,checksumStorage=%.1fMB," +
                               "features=batch=%s,mmap=%s,integrity=%s}",
                    batchLoaders, batchSavers, mmapEngines, integrityValidators,
                    totalLoaded, totalDecompressed, getCacheHitRate(), getCorruptionRate(),
                    mmapMemoryUsage / 1024.0 / 1024.0, totalChecksumStorage / 1024.0 / 1024.0,
                    batchEnabled, mmapEnabled, integrityEnabled);
        }
    }
}
