package com.turbomc.storage;

import com.turbomc.compression.TurboCompressionService;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance batch chunk loader for LRF regions.
 * Provides efficient concurrent chunk loading with decompression and caching.
 * 
 * Features:
 * - Parallel chunk loading with multiple threads
 * - Asynchronous decompression pipeline
 * - Memory-efficient batch processing
 * - Progress tracking and statistics
 * - Configurable batch sizes and thread pools
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class ChunkBatchLoader implements AutoCloseable {
    
    private final Path regionPath;
    private final ExecutorService loadExecutor;
    private final ExecutorService decompressionExecutor;
    private final ConcurrentHashMap<Integer, CompletableFuture<LRFChunkEntry>> loadingChunks;
    private final AtomicBoolean isClosed;
    
    // Configuration
    private final int loadThreads;
    private final int decompressionThreads;
    private final int maxBatchSize;
    private final int maxConcurrentLoads;
    
    // Statistics
    private final AtomicInteger chunksLoaded;
    private final AtomicInteger chunksDecompressed;
    private final AtomicInteger cacheHits;
    private final AtomicInteger cacheMisses;
    private final AtomicLong totalLoadTime;
    private final AtomicLong totalDecompressionTime;
    
    // LRF reader cache
    private volatile LRFRegionReader regionReader;
    private final Object readerLock = new Object();
    private final long readerRefreshIntervalMs;
    private volatile long lastReaderRefresh;
    
    /**
     * Create a new batch loader with default configuration.
     * 
     * @param regionPath Path to the LRF region file
     * @throws IOException if file cannot be opened
     */
    public ChunkBatchLoader(Path regionPath) throws IOException {
        this(regionPath, 
             Runtime.getRuntime().availableProcessors() / 2,
             Math.max(1, Runtime.getRuntime().availableProcessors() / 4),
             32,  // max batch size
             64); // max concurrent loads
    }
    
    /**
     * Create a new batch loader with custom configuration.
     * 
     * @param regionPath Path to the LRF region file
     * @param loadThreads Number of I/O threads
     * @param decompressionThreads Number of decompression threads
     * @param maxBatchSize Maximum chunks per batch
     * @param maxConcurrentLoads Maximum concurrent loading operations
     * @throws IOException if file cannot be opened
     */
    public ChunkBatchLoader(Path regionPath, int loadThreads, int decompressionThreads,
                           int maxBatchSize, int maxConcurrentLoads) throws IOException {
        this.regionPath = regionPath;
        this.loadThreads = loadThreads;
        this.decompressionThreads = decompressionThreads;
        this.maxBatchSize = maxBatchSize;
        this.maxConcurrentLoads = maxConcurrentLoads;
        this.readerRefreshIntervalMs = 60000; // 1 minute
        
        this.loadExecutor = Executors.newFixedThreadPool(loadThreads, r -> {
            Thread t = new Thread(r, "ChunkBatchLoader-IO-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        
        this.decompressionExecutor = Executors.newFixedThreadPool(decompressionThreads, r -> {
            Thread t = new Thread(r, "ChunkBatchLoader-Decompress-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        
        this.loadingChunks = new ConcurrentHashMap<>();
        this.isClosed = new AtomicBoolean(false);
        this.chunksLoaded = new AtomicInteger(0);
        this.chunksDecompressed = new AtomicInteger(0);
        this.cacheHits = new AtomicInteger(0);
        this.cacheMisses = new AtomicInteger(0);
        this.totalLoadTime = new AtomicLong(0);
        this.totalDecompressionTime = new AtomicLong(0);
        this.lastReaderRefresh = System.currentTimeMillis();
        
        // Initialize region reader
        refreshRegionReader();
        
        System.out.println("[TurboMC] ChunkBatchLoader initialized: " + regionPath.getFileName() +
                         " (load threads: " + loadThreads +
                         ", decompress threads: " + decompressionThreads +
                         ", batch size: " + maxBatchSize +
                         ", concurrent loads: " + maxConcurrentLoads + ")");
    }
    
    /**
     * Load a single chunk asynchronously.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return CompletableFuture that completes with the chunk data, or null if chunk doesn't exist
     * @throws IllegalStateException if loader is closed
     */
    public CompletableFuture<LRFChunkEntry> loadChunk(int chunkX, int chunkZ) {
        if (isClosed.get()) {
            throw new IllegalStateException("ChunkBatchLoader is closed");
        }
        
        int chunkIndex = LRFConstants.getChunkIndex(chunkX, chunkZ);
        
        // Check if already loading
        CompletableFuture<LRFChunkEntry> existing = loadingChunks.get(chunkIndex);
        if (existing != null) {
            cacheHits.incrementAndGet();
            return existing;
        }
        
        // Check concurrent load limit
        if (loadingChunks.size() >= maxConcurrentLoads) {
            return CompletableFuture.completedFuture(null); // Backpressure
        }
        
        cacheMisses.incrementAndGet();
        
        // Create new loading future
        CompletableFuture<LRFChunkEntry> future = CompletableFuture
            .supplyAsync(() -> loadChunkDirect(chunkX, chunkZ), loadExecutor)
            .thenComposeAsync(this::decompressChunk, decompressionExecutor)
            .whenComplete((result, throwable) -> {
                loadingChunks.remove(chunkIndex);
                if (throwable != null) {
                    System.err.println("[TurboMC] Error loading chunk " + chunkX + "," + chunkZ + ": " + throwable.getMessage());
                } else if (result != null) {
                    chunksLoaded.incrementAndGet();
                }
            });
        
        loadingChunks.put(chunkIndex, future);
        return future;
    }
    
    /**
     * Load multiple chunks in parallel.
     * 
     * @param chunkCoords List of chunk coordinate pairs [x, z]
     * @return CompletableFuture that completes with list of loaded chunks
     */
    public CompletableFuture<List<LRFChunkEntry>> loadChunks(List<int[]> chunkCoords) {
        List<CompletableFuture<LRFChunkEntry>> futures = new ArrayList<>();
        
        // Split into batches to avoid overwhelming the system
        for (int i = 0; i < chunkCoords.size(); i += maxBatchSize) {
            int endIndex = Math.min(i + maxBatchSize, chunkCoords.size());
            List<int[]> batch = chunkCoords.subList(i, endIndex);
            
            for (int[] coords : batch) {
                futures.add(loadChunk(coords[0], coords[1]));
            }
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                List<LRFChunkEntry> results = new ArrayList<>();
                for (CompletableFuture<LRFChunkEntry> future : futures) {
                    try {
                        LRFChunkEntry chunk = future.get();
                        if (chunk != null) {
                            results.add(chunk);
                        }
                    } catch (Exception e) {
                        // Skip failed chunks
                    }
                }
                return results;
            });
    }
    
    /**
     * Load chunks in a rectangular region.
     * 
     * @param centerX Center chunk X coordinate
     * @param centerZ Center chunk Z coordinate
     * @param radius Radius in chunks
     * @return CompletableFuture that completes with list of loaded chunks
     */
    public CompletableFuture<List<LRFChunkEntry>> loadRegion(int centerX, int centerZ, int radius) {
        List<int[]> coords = new ArrayList<>();
        
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                coords.add(new int[]{centerX + dx, centerZ + dz});
            }
        }
        
        return loadChunks(coords);
    }
    
    /**
     * Load chunk directly from file (I/O operation).
     */
    private LRFChunkEntry loadChunkDirect(int chunkX, int chunkZ) {
        long startTime = System.currentTimeMillis();
        
        try {
            // Refresh region reader if needed
            if (System.currentTimeMillis() - lastReaderRefresh > readerRefreshIntervalMs) {
                refreshRegionReader();
            }
            
            LRFRegionReader reader = getRegionReader();
            if (reader == null) {
                return null;
            }
            
            LRFChunkEntry chunk = reader.readChunk(chunkX, chunkZ);
            totalLoadTime.addAndGet(System.currentTimeMillis() - startTime);
            return chunk;
            
        } catch (IOException e) {
            System.err.println("[TurboMC] Failed to load chunk " + chunkX + "," + chunkZ + ": " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Decompress chunk data if needed.
     */
    private CompletableFuture<LRFChunkEntry> decompressChunk(LRFChunkEntry chunk) {
        if (chunk == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                byte[] data = chunk.getData();
                
                // Check if data needs decompression
                // LZ4 compressed data typically starts with specific magic bytes
                boolean needsDecompression = data.length > 4 && 
                    (data[0] == 0x04 || data[0] == 0x02); // LZ4 frame markers
                
                if (needsDecompression) {
                    byte[] decompressed = TurboCompressionService.getInstance().decompress(data);
                    
                    // Remove timestamp from end if present (8 bytes)
                    if (decompressed.length > 8) {
                        byte[] chunkData = new byte[decompressed.length - 8];
                        System.arraycopy(decompressed, 0, chunkData, 0, chunkData.length);
                        long timestamp = ByteBuffer.wrap(decompressed, decompressed.length - 8, 8).getLong();
                        return new LRFChunkEntry(chunk.getChunkX(), chunk.getChunkZ(), chunkData, timestamp);
                    }
                    
                    return new LRFChunkEntry(chunk.getChunkX(), chunk.getChunkZ(), decompressed);
                }
                
                chunksDecompressed.incrementAndGet();
                totalDecompressionTime.addAndGet(System.currentTimeMillis() - startTime);
                return chunk;
                
            } catch (Exception e) {
                System.err.println("[TurboMC] Failed to decompress chunk " + 
                                 chunk.getChunkX() + "," + chunk.getChunkZ() + ": " + e.getMessage());
                return chunk; // Return original chunk if decompression fails
            }
        }, decompressionExecutor);
    }
    
    /**
     * Get or refresh the region reader.
     */
    private LRFRegionReader getRegionReader() {
        LRFRegionReader reader = regionReader;
        if (reader == null) {
            synchronized (readerLock) {
                reader = regionReader;
                if (reader == null) {
                    try {
                        reader = new LRFRegionReader(regionPath);
                        regionReader = reader;
                        lastReaderRefresh = System.currentTimeMillis();
                    } catch (IOException e) {
                        System.err.println("[TurboMC] Failed to open region reader: " + e.getMessage());
                        return null;
                    }
                }
            }
        }
        return reader;
    }
    
    /**
     * Refresh the region reader to pick up any file changes.
     */
    private void refreshRegionReader() {
        synchronized (readerLock) {
            if (regionReader != null) {
                try {
                    regionReader.close();
                } catch (IOException e) {
                    // Ignore close errors
                }
            }
            
            try {
                regionReader = new LRFRegionReader(regionPath);
                lastReaderRefresh = System.currentTimeMillis();
            } catch (IOException e) {
                System.err.println("[TurboMC] Failed to refresh region reader: " + e.getMessage());
                regionReader = null;
            }
        }
    }
    
    /**
     * Wait for all pending loading operations to complete.
     * 
     * @param timeout Maximum time to wait
     * @param unit Time unit
     * @return True if all operations completed, false if timeout
     */
    public boolean awaitCompletion(long timeout, TimeUnit unit) {
        try {
            // Wait for all loading futures
            for (CompletableFuture<LRFChunkEntry> future : loadingChunks.values()) {
                future.get(timeout, unit);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get loading statistics.
     */
    public BatchLoaderStats getStats() {
        return new BatchLoaderStats(
            chunksLoaded.get(),
            chunksDecompressed.get(),
            cacheHits.get(),
            cacheMisses.get(),
            totalLoadTime.get(),
            totalDecompressionTime.get(),
            loadingChunks.size(),
            loadThreads,
            decompressionThreads
        );
    }
    
    /**
     * Get number of currently loading chunks.
     */
    public int getLoadingCount() {
        return loadingChunks.size();
    }
    
    /**
     * Check if loader is closed.
     */
    public boolean isClosed() {
        return isClosed.get();
    }
    
    @Override
    public void close() throws IOException {
        if (isClosed.compareAndSet(false, true)) {
            try {
                // Wait for pending operations
                awaitCompletion(30, TimeUnit.SECONDS);
                
                // Shutdown executors
                loadExecutor.shutdown();
                decompressionExecutor.shutdown();
                
                if (!loadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    loadExecutor.shutdownNow();
                }
                
                if (!decompressionExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    decompressionExecutor.shutdownNow();
                }
                
                // Close region reader
                synchronized (readerLock) {
                    if (regionReader != null) {
                        regionReader.close();
                        regionReader = null;
                    }
                }
                
                System.out.println("[TurboMC] ChunkBatchLoader closed: " + getStats());
            } catch (Exception e) {
                throw new IOException("Error closing ChunkBatchLoader", e);
            }
        }
    }
    
    /**
     * Statistics for batch loader performance.
     */
    public static class BatchLoaderStats {
        private final int chunksLoaded;
        private final int chunksDecompressed;
        private final int cacheHits;
        private final int cacheMisses;
        private final long totalLoadTime;
        private final long totalDecompressionTime;
        private final int currentlyLoading;
        private final int loadThreads;
        private final int decompressionThreads;
        
        BatchLoaderStats(int chunksLoaded, int chunksDecompressed, int cacheHits, int cacheMisses,
                         long totalLoadTime, long totalDecompressionTime, int currentlyLoading,
                         int loadThreads, int decompressionThreads) {
            this.chunksLoaded = chunksLoaded;
            this.chunksDecompressed = chunksDecompressed;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.totalLoadTime = totalLoadTime;
            this.totalDecompressionTime = totalDecompressionTime;
            this.currentlyLoading = currentlyLoading;
            this.loadThreads = loadThreads;
            this.decompressionThreads = decompressionThreads;
        }
        
        public int getChunksLoaded() { return chunksLoaded; }
        public int getChunksDecompressed() { return chunksDecompressed; }
        public int getCacheHits() { return cacheHits; }
        public int getCacheMisses() { return cacheMisses; }
        public long getTotalLoadTime() { return totalLoadTime; }
        public long getTotalDecompressionTime() { return totalDecompressionTime; }
        public int getCurrentlyLoading() { return currentlyLoading; }
        public int getLoadThreads() { return loadThreads; }
        public int getDecompressionThreads() { return decompressionThreads; }
        
        public double getCacheHitRate() {
            int total = cacheHits + cacheMisses;
            return total > 0 ? (double) cacheHits / total * 100 : 0;
        }
        
        public double getAvgLoadTime() {
            return chunksLoaded > 0 ? (double) totalLoadTime / chunksLoaded : 0;
        }
        
        public double getAvgDecompressionTime() {
            return chunksDecompressed > 0 ? (double) totalDecompressionTime / chunksDecompressed : 0;
        }
        
        @Override
        public String toString() {
            return String.format("BatchLoaderStats{loaded=%d,decompressed=%d,cache=%.1f%%,avgLoad=%.2fms,avgDecompress=%.2fms,loading=%d,threads=%d+%d}",
                    chunksLoaded, chunksDecompressed, getCacheHitRate(), 
                    getAvgLoadTime(), getAvgDecompressionTime(), currentlyLoading,
                    loadThreads, decompressionThreads);
        }
    }
}
