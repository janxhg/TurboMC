package com.turbomc.storage.optimization;

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
import java.util.concurrent.ExecutorService;
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
    private final ConcurrentHashMap<Path, LRFRegionReader> regionReaders;
    private final ConcurrentHashMap<Path, ChunkIntegrityValidator> integrityValidators;
    private final ConcurrentHashMap<Path, SharedRegionResource> sharedResources;
    
    // Configuration
    private final TurboConfig config;
    private final AtomicBoolean isInitialized;
    private final AtomicBoolean isClosed;
    
    // Feature flags
    private final boolean batchEnabled;
    private final boolean mmapEnabled;
    private final boolean integrityEnabled;
    
    // Global shared executors to prevent thread explosion per region
    private ExecutorService globalLoadExecutor;
    private ExecutorService globalWriteExecutor;
    private ExecutorService globalCompressionExecutor;
    private ExecutorService globalDecompressionExecutor;
    private ExecutorService globalPrefetchExecutor; // Dedicated pool for prefetching
    
    private TurboStorageManager(TurboConfig config) {
        this.config = config;
        this.batchLoaders = new ConcurrentHashMap<>();
        this.batchSavers = new ConcurrentHashMap<>();
        this.readAheadEngines = new ConcurrentHashMap<>();
        this.regionReaders = new ConcurrentHashMap<>();
        this.integrityValidators = new ConcurrentHashMap<>();
        this.sharedResources = new ConcurrentHashMap<>();
        this.isInitialized = new AtomicBoolean(false);
        this.isClosed = new AtomicBoolean(false);
        
        // Initialize global executors if batching is enabled
        if (config.getBoolean("storage.batch.enabled", true)) {
            // Set LRF verbosity
            LRFRegionWriter.setVerbose(config.getBoolean("storage.lrf.verbose", false));
            
            int processors = Runtime.getRuntime().availableProcessors();
            
            // FIX: Cap thread counts to prevent explosion on high-core systems
            final int MAX_LOAD_THREADS = 16;
            final int MAX_WRITE_THREADS = 8;
            final int MAX_COMPRESSION_THREADS = 16;
            final int MAX_DECOMPRESSION_THREADS = 32;
            
            // Scalable thread pool sizes with caps
            int loadThreads = Math.max(2, Math.min(config.getInt("storage.batch.global-load-threads", processors / 4), MAX_LOAD_THREADS));
            int writeThreads = Math.max(1, Math.min(config.getInt("storage.batch.global-save-threads", processors / 8), MAX_WRITE_THREADS));
            int compressionThreads = Math.max(2, Math.min(config.getInt("storage.batch.global-compression-threads", processors / 2), MAX_COMPRESSION_THREADS));
            int decompressionThreads = Math.max(2, Math.min(config.getInt("storage.batch.global-decompression-threads", processors / 2), MAX_COMPRESSION_THREADS));
            
            // FIX: Create daemon threads to allow JVM shutdown
            this.globalLoadExecutor = java.util.concurrent.Executors.newFixedThreadPool(loadThreads, r -> {
                Thread t = new Thread(r, "Turbo-Global-LoadPool");
                t.setDaemon(true);  // CRITICAL: Allow JVM to shutdown
                return t;
            });
            
            this.globalWriteExecutor = java.util.concurrent.Executors.newFixedThreadPool(writeThreads, r -> {
                Thread t = new Thread(r, "Turbo-Global-WritePool");
                t.setDaemon(true);
                return t;
            });
            
            this.globalCompressionExecutor = java.util.concurrent.Executors.newFixedThreadPool(compressionThreads, r -> {
                Thread t = new Thread(r, "Turbo-Global-CompressionPool");
                t.setDaemon(true);
                return t;
            });
            
            this.globalDecompressionExecutor = java.util.concurrent.Executors.newFixedThreadPool(decompressionThreads, r -> {
                Thread t = new Thread(r, "Turbo-Global-DecompressionPool");
                t.setDaemon(true);
                return t;
            });
            
            // Dedicated prefetch pool - lower priority
            this.globalPrefetchExecutor = java.util.concurrent.Executors.newFixedThreadPool(Math.max(2, loadThreads), r -> {
                Thread t = new Thread(r, "Turbo-Global-PrefetchPool");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY); // Background priority
                return t;
            });
                
            System.out.println("[TurboMC][Storage] Global thread pools initialized (" + 
                loadThreads + "L, " + writeThreads + "W, " + compressionThreads + "C, " + decompressionThreads + "D, " + Math.max(2, loadThreads) + "P)");
        }
        
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
     * Resets the singleton instance for testing purposes.
     */
    public static void resetInstance() {
        synchronized (INSTANCE_LOCK) {
            if (instance != null) {
                try {
                    instance.close();
                } catch (Exception ignored) {}
                instance = null;
            }
        }
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
     * Check if a chunk is immediately available in cache or pending writes.
     * v2.1 Optimization: Fast path for Moonrise status checks.
     */
    public boolean hasDataFor(Path regionPath, int chunkX, int chunkZ) {
        if (isClosed.get()) return false;
        Path finalPath = normalizePath(regionPath);
        
        // Check MMap cache
        if (mmapEnabled) {
            MMapReadAheadEngine engine = readAheadEngines.get(finalPath);
            if (engine != null && engine.isCached(chunkX, chunkZ)) {
                return true;
            }
        }
        
        // Check pending writes
        if (batchEnabled) {
            ChunkBatchSaver saver = batchSavers.get(finalPath);
            if (saver != null && saver.hasPendingChunk(chunkX, chunkZ)) {
                return true;
            }
        }
        
        return false;
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
        
        final Path finalPath = normalizePath(regionPath);
        
        // Try memory-mapped read-ahead first (fastest)
        if (mmapEnabled) {
            MMapReadAheadEngine mmapEngine = getReadAheadEngine(finalPath);
            if (mmapEngine != null) {
                try {
                    byte[] data = mmapEngine.readChunk(chunkX, chunkZ);
                    if (data != null) {
                        LRFChunkEntry chunk = new LRFChunkEntry(chunkX, chunkZ, data);
                        
                        // Validate integrity if enabled
                        if (integrityEnabled) {
                            return validateChunk(finalPath, chunkX, chunkZ, data)
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
        
        // Check pending writes in BatchSaver (Read-Your-Writes Consistency)
        // This is critical for generation performance to avoid checking disk for chunks currently in write buffer
        if (batchEnabled) {
            ChunkBatchSaver saver = batchSavers.get(finalPath);
            if (saver != null) {
                LRFChunkEntry pendingChunk = saver.getPendingChunk(chunkX, chunkZ);
                if (pendingChunk != null) {
                    return CompletableFuture.completedFuture(pendingChunk);
                }
            }
        }
        
        // Fallback to batch loader
        if (batchEnabled) {
            ChunkBatchLoader loader = getBatchLoader(finalPath);
            if (loader != null) {
                CompletableFuture<LRFChunkEntry> future = loader.loadChunk(chunkX, chunkZ);
                
                // Validate integrity if enabled
                if (integrityEnabled) {
                    return future.thenCompose(chunk -> {
                        if (chunk != null) {
                            return validateChunk(finalPath, chunkX, chunkZ, chunk.getData())
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
                SharedRegionResource resource = getSharedResource(finalPath);
                LRFRegionReader reader = getRegionReader(finalPath);
                if (reader != null) {
                    return reader.readChunk(chunkX, chunkZ);
                }
                return null;
            } catch (IOException e) {
                System.err.println("[TurboMC][Storage] Failed to load chunk " + chunkX + "," + chunkZ + ": " + e.getMessage());
                return null;
            }
        }, globalLoadExecutor);
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
        
        final Path finalPath = normalizePath(regionPath);
        
        // Defensively copy the data to avoid issues with Paper reusing NBT buffers
        byte[] dataCopy = new byte[data.length];
        System.arraycopy(data, 0, dataCopy, 0, data.length);
        
        LRFChunkEntry chunk = new LRFChunkEntry(chunkX, chunkZ, dataCopy);
        return saveChunkInternal(finalPath, chunk);
    }
    
    /**
     * Internal chunk saving method.
     */
    private CompletableFuture<Void> saveChunkInternal(final Path finalPath, LRFChunkEntry chunk) {
        if (batchEnabled) {
            ChunkBatchSaver saver = getBatchSaver(finalPath);
            if (saver != null) {
                return saver.saveChunk(chunk);
            }
        }
        
        // Fallback to direct LRF writer
        return CompletableFuture.runAsync(() -> {
            try {
                SharedRegionResource resource = getSharedResource(finalPath);
                try (LRFRegionWriter writer = new LRFRegionWriter(resource, chunk.getData() != null ? LRFConstants.COMPRESSION_LZ4 : LRFConstants.COMPRESSION_NONE)) {
                    writer.addChunk(chunk);
                    writer.flush();
                    
                    if (integrityEnabled) {
                        ChunkIntegrityValidator validator = getIntegrityValidator(finalPath);
                        if (validator != null) {
                            validator.updateChecksum(chunk.getChunkX(), chunk.getChunkZ(), chunk.getData());
                        }
                    }
                    
                    // Invalidate header cache
                    resource.invalidateHeader();
                }
            } catch (IOException e) {
                System.err.println("[TurboMC][Storage] Failed to save chunk " + 
                                 chunk.getChunkX() + "," + chunk.getChunkZ() + ": " + e.getMessage());
                throw new RuntimeException(e);
            }
        }, globalWriteExecutor);
    }
    
    /**
     * Flush all pending writes for a region.
     * 
     * @param regionPath Path to the region file
     * @return CompletableFuture that completes when flush is done
     */
    public CompletableFuture<Void> flush(Path regionPath) {
        Path finalPath = normalizePath(regionPath);
        ChunkBatchSaver saver = batchSavers.get(finalPath);
        if (saver != null) {
            return saver.flushBatch();
        }
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Load multiple chunks in parallel.
     * 
     * @param regionPath Path to the region file
     * @param chunkCoords List of chunk coordinate pairs [x, z]
     * @return CompletableFuture that completes with list of loaded chunks
     */
    public CompletableFuture<java.util.List<LRFChunkEntry>> loadChunks(Path regionPath, java.util.List<int[]> chunkCoords) {
        regionPath = normalizePath(regionPath);
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
        regionPath = normalizePath(regionPath);
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
        
        Path finalPath = normalizePath(regionPath);
        return batchLoaders.computeIfAbsent(finalPath, path -> {
            try {
                SharedRegionResource resource = getSharedResource(path);
                int batchSize = config.getInt("storage.batch.batch-size", 32);
                int maxConcurrentLoads = config.getInt("storage.batch.max-concurrent-loads", 64);
                
                // Use global shared executors
                return new ChunkBatchLoader(resource, globalLoadExecutor, globalDecompressionExecutor,
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
        
        Path finalPath = normalizePath(regionPath);
        return batchSavers.computeIfAbsent(finalPath, path -> {
            try {
                SharedRegionResource resource = getSharedResource(path);
                int batchSize = config.getInt("storage.batch.batch-size", 32);
                long autoFlushDelay = config.getLong("storage.batch.auto-flush-delay", 500);
                int compressionType = LRFConstants.COMPRESSION_LZ4; // Default to LZ4
                
                // Use global shared executors
                ChunkBatchSaver saver = new ChunkBatchSaver(resource, compressionType, globalCompressionExecutor, 
                                         globalWriteExecutor, batchSize, autoFlushDelay);
                
                // Unified post-flush action for extreme efficiency
                saver.setPostFlushAction((chunks) -> {
                    // Update integrity checksums for all chunks in batch AT ONCE
                    if (integrityEnabled) {
                        ChunkIntegrityValidator validator = getIntegrityValidator(path);
                        if (validator != null) {
                            for (LRFChunkEntry chunk : chunks) {
                                validator.updateChecksum(chunk.getChunkX(), chunk.getChunkZ(), chunk.getData());
                            }
                        }
                    }
                    
                    // Invalidate header cache ONCE per batch flush
                    resource.invalidateHeader();
                });
                
                return saver;
            } catch (IOException e) {
                System.err.println("[TurboMC][Storage] Failed to create batch saver for " + path + ": " + e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * Get or create a memory-mapped read-ahead engine for the specified region.
     */
    private MMapReadAheadEngine getReadAheadEngine(Path regionPath) {
        if (!mmapEnabled) return null;
        
        Path finalPath = normalizePath(regionPath);
        return readAheadEngines.computeIfAbsent(finalPath, path -> {
            try {
                SharedRegionResource resource = getSharedResource(path);
                int maxCacheSize = config.getInt("storage.mmap.max-cache-size", 512);
                int prefetchDistance = config.getInt("storage.mmap.prefetch-distance", 8);
                int prefetchBatchSize = config.getInt("storage.mmap.prefetch-batch-size", 32);
                long maxMemoryUsage = config.getLong("storage.mmap.max-memory-usage", 256) * 1024 * 1024; // MB to bytes
                
                boolean predictive = config.getBoolean("storage.mmap.predictive-enabled", true);
                int predictionScale = config.getInt("storage.mmap.prediction-scale", 12);
                
                return new MMapReadAheadEngine(resource, maxCacheSize, prefetchDistance, 
                                             prefetchBatchSize, maxMemoryUsage, predictive, predictionScale,
                                             globalPrefetchExecutor);
            } catch (IOException e) {
                System.err.println("[TurboMC][Storage] Failed to create MMap engine for " + path + ": " + e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * Get or create a region reader for the specified region.
     */
    private LRFRegionReader getRegionReader(Path regionPath) {
        Path finalPath = normalizePath(regionPath);
        return regionReaders.computeIfAbsent(finalPath, path -> {
            try {
                SharedRegionResource resource = getSharedResource(path);
                return new LRFRegionReader(resource);
            } catch (IOException e) {
                System.err.println("[TurboMC][Storage] Failed to create region reader for " + path + ": " + e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * Get or create an integrity validator for the specified region.
     */
    private ChunkIntegrityValidator getIntegrityValidator(Path regionPath) {
        if (!integrityEnabled) return null;
        
        Path finalPath = normalizePath(regionPath);
        return integrityValidators.computeIfAbsent(finalPath, path -> {
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
            regionPath = normalizePath(regionPath);
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
            
            LRFRegionReader reader = regionReaders.remove(regionPath);
            if (reader != null) {
                reader.close();
            }
            
            ChunkIntegrityValidator validator = integrityValidators.remove(regionPath);
            if (validator != null) {
                validator.close();
            }
            
            SharedRegionResource resource = sharedResources.remove(regionPath);
            if (resource != null) {
                resource.close();
            }
            
            System.out.println("[TurboMC][Storage] Closed storage components for: " + regionPath.getFileName());
        } catch (IOException e) {
            System.err.println("[TurboMC][Storage] Error closing region " + regionPath + ": " + e.getMessage());
        }
    }
    
    /**
     * Get or create a shared resource for a region.
     */
    private SharedRegionResource getSharedResource(Path regionPath) throws IOException {
        Path finalPath = normalizePath(regionPath);
        SharedRegionResource resource = sharedResources.get(finalPath);
        if (resource == null) {
            resource = new SharedRegionResource(finalPath);
            SharedRegionResource existing = sharedResources.putIfAbsent(finalPath, resource);
            if (existing != null) {
                resource.close(); // Not needed
                resource = existing;
            }
        }
        return resource;
    }
    
    /**
     * Normalize path for map keys.
     */
    private Path normalizePath(Path path) {
        if (path == null) return null;
        return path.toAbsolutePath().normalize();
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
            
            for (LRFRegionReader reader : regionReaders.values()) {
                try {
                    reader.close();
                } catch (Exception e) {
                    System.err.println("[TurboMC][Storage] Error closing region reader: " + e.getMessage());
                }
            }
            
            // Clear collections
            batchLoaders.clear();
            batchSavers.clear();
            readAheadEngines.clear();
            regionReaders.clear();
            integrityValidators.clear();
            
            // Shut down global executors
            shutdownExecutor(globalLoadExecutor, "LoadPool");
            shutdownExecutor(globalWriteExecutor, "WritePool");
            shutdownExecutor(globalCompressionExecutor, "CompressionPool");
            shutdownExecutor(globalDecompressionExecutor, "DecompressionPool");
            shutdownExecutor(globalPrefetchExecutor, "PrefetchPool");
            
            System.out.println("[TurboMC][Storage] Final stats: " + getStats());
            System.out.println("[TurboMC][Storage] Storage manager shutdown complete.");
        }
    }
    
    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null) return;
        
        try {
            System.out.println("[TurboMC][Storage] Shutting down " + name + "...");
            executor.shutdown();
            
            // FIX: Use longer timeout for write operations, shorter for others
            int timeoutSeconds = name.contains("Write") ? 30 : 10;
            
            if (!executor.awaitTermination(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                System.err.println("[TurboMC][Storage][WARN] " + name + " didn't terminate in " + timeoutSeconds + "s, forcing shutdown...");
                java.util.List<Runnable> remainingTasks = executor.shutdownNow();
                
                if (!remainingTasks.isEmpty()) {
                    System.err.println("[TurboMC][Storage][WARN] " + name + " had " + remainingTasks.size() + " pending tasks");
                }
                
                // Give 5 more seconds for forced shutdown
                if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    System.err.println("[TurboMC][Storage][ERROR] " + name + " still has running tasks after forced shutdown!");
                }
            } else {
                System.out.println("[TurboMC][Storage] " + name + " shutdown cleanly");
            }
        } catch (InterruptedException e) {
            System.err.println("[TurboMC][Storage][ERROR] Interrupted while shutting down " + name);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Get count of active regions.
     */
    public int getActiveRegionCount() {
        return sharedResources.size();
    }
    
    /**
     * Get batch loader count.
     */
    public int getBatchLoaderCount() {
        return batchLoaders.size();
    }
    
    /**
     * Get batch saver count.
     */
    public int getBatchSaverCount() {
        return batchSavers.size();
    }
    
    /**
     * Get total cache hits across all engines.
     */
    public long getCacheHits() {
        return readAheadEngines.values().stream()
            .mapToLong(engine -> engine.getCacheHits())
            .sum();
    }
    
    /**
     * Get total cache misses across all engines.
     */
    public long getCacheMisses() {
        return readAheadEngines.values().stream()
            .mapToLong(engine -> engine.getCacheMisses())
            .sum();
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
