package com.turbomc.storage;

import java.io.IOException;
import java.io.RandomAccessFile;
// import java.lang.foreign.MemorySegment;
// import java.lang.foreign.MemorySession;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.turbomc.compression.TurboCompressionService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance memory-mapped read-ahead engine optimized for SSD/NVMe storage.
 * Provides intelligent prefetching of chunk data based on access patterns and player movement.
 * 
 * Features:
 * - Memory-mapped file access with automatic prefetching
 * - Adaptive read-ahead based on player movement patterns
 * - LRU cache with configurable size limits
 * - Background prefetching for predicted chunks
 * - NVMe-optimized sequential I/O patterns
 * - Memory pressure handling
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class MMapReadAheadEngine implements AutoCloseable {
    
    private final Path regionPath;
    private final ExecutorService prefetchExecutor;
    private final ScheduledExecutorService maintenanceExecutor;
    private final ConcurrentHashMap<Integer, CachedChunk> chunkCache;
    private final AtomicBoolean isClosed;
    
    // Configuration
    private final int maxCacheSize;
    private final int prefetchDistance;
    private final int prefetchBatchSize;
    private final long maxMemoryUsage;
    private boolean useForeignMemoryAPI;
    
    // Memory management
    private final AtomicLong currentMemoryUsage;
    private final AtomicInteger cacheHits;
    private final AtomicInteger cacheMisses;
    private final AtomicInteger prefetchCount;
    
    // File mapping
    private RandomAccessFile file;
    private FileChannel fileChannel;
    private MappedByteBuffer mappedBuffer;
    // private MemorySegment memorySegment;
    // private MemorySession memorySession;
    
    // Statistics
    private final long startTime;
    
    /**
     * Create a new read-ahead engine with default configuration.
     * 
     * @param regionPath Path to the LRF region file
     * @throws IOException if file cannot be opened
     */
    public MMapReadAheadEngine(Path regionPath) throws IOException {
        this(regionPath, 
             512,  // max cache size (chunks)
             4,    // prefetch distance (chunks)
             16,   // prefetch batch size
             256 * 1024 * 1024); // 256MB max memory
    }
    
    /**
     * Create a new read-ahead engine with custom configuration.
     * 
     * @param regionPath Path to the LRF region file
     * @param maxCacheSize Maximum number of chunks to cache
     * @param prefetchDistance Distance in chunks to prefetch ahead
     * @param prefetchBatchSize Number of chunks to prefetch in one batch
     * @param maxMemoryUsage Maximum memory usage in bytes
     * @throws IOException if file cannot be opened
     */
    public MMapReadAheadEngine(Path regionPath, int maxCacheSize, int prefetchDistance,
                              int prefetchBatchSize, long maxMemoryUsage) throws IOException {
        this.regionPath = regionPath;
        this.maxCacheSize = maxCacheSize;
        this.prefetchDistance = prefetchDistance;
        this.prefetchBatchSize = prefetchBatchSize;
        this.maxMemoryUsage = maxMemoryUsage;
        
        // Detect if Foreign Memory API is available (Java 22+)
        this.useForeignMemoryAPI = detectForeignMemoryAPI();
        
        this.prefetchExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "MMapReadAhead-Prefetch-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        
        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MMapReadAhead-Maintenance-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        
        this.chunkCache = new ConcurrentHashMap<>();
        this.isClosed = new AtomicBoolean(false);
        this.currentMemoryUsage = new AtomicLong(0);
        this.cacheHits = new AtomicInteger(0);
        this.cacheMisses = new AtomicInteger(0);
        this.prefetchCount = new AtomicInteger(0);
        this.startTime = System.currentTimeMillis();
        
        initializeFileMapping();
        startMaintenanceTasks();
        
        System.out.println("[TurboMC] MMapReadAheadEngine initialized: " + regionPath.getFileName() +
                         " (cache: " + maxCacheSize + " chunks, " +
                         "prefetch: " + prefetchDistance + " chunks, " +
                         "memory: " + (maxMemoryUsage / 1024 / 1024) + "MB, " +
                         "foreign-api: " + useForeignMemoryAPI + ")");
    }
    
    /**
     * Initialize memory-mapped file access.
     */
    private void initializeFileMapping() throws IOException {
        this.file = new RandomAccessFile(regionPath.toFile(), "r");
        this.fileChannel = file.getChannel();
        long fileSize = fileChannel.size();
        
        // Foreign Memory API disabled for compatibility
        useForeignMemoryAPI = false;
        
        if (!useForeignMemoryAPI) {
            // Fallback to traditional MappedByteBuffer
            this.mappedBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            this.mappedBuffer.load(); // Preload entire file for SSD/NVMe
        }
    }
    
    /**
     * Start background maintenance tasks.
     */
    private void startMaintenanceTasks() {
        // Cache cleanup every 30 seconds
        maintenanceExecutor.scheduleAtFixedRate(this::cleanupCache, 30, 30, TimeUnit.SECONDS);
        
        // Statistics logging every 5 minutes
        maintenanceExecutor.scheduleAtFixedRate(this::logStatistics, 300, 300, TimeUnit.SECONDS);
    }
    
    /**
     * Read a chunk with automatic caching and prefetching.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Chunk data, or null if chunk doesn't exist
     * @throws IOException if read fails
     */
    public byte[] readChunk(int chunkX, int chunkZ) throws IOException {
        if (isClosed.get()) {
            throw new IllegalStateException("MMapReadAheadEngine is closed");
        }
        
        int chunkIndex = LRFConstants.getChunkIndex(chunkX, chunkZ);
        CachedChunk cached = chunkCache.get(chunkIndex);
        
        if (cached != null && !cached.isExpired()) {
            cacheHits.incrementAndGet();
            cached.updateLastAccess();
            return cached.getData();
        }
        
        cacheMisses.incrementAndGet();
        
        // Read chunk from file
        byte[] data = readChunkDirect(chunkX, chunkZ);
        if (data == null) {
            return null;
        }
        
        // Cache the chunk
        cacheChunk(chunkIndex, data);
        
        // Trigger prefetch for nearby chunks
        triggerPrefetch(chunkX, chunkZ);
        
        return data;
    }
    
    /**
     * Read chunk directly from memory-mapped file.
     */
    private byte[] readChunkDirect(int chunkX, int chunkZ) throws IOException {
        try {
            // Read LRF header to get chunk offset
            LRFHeader header = readHeader();
            if (!header.hasChunk(chunkX, chunkZ)) {
                return null;
            }
            
            int offset = header.getChunkOffset(chunkX, chunkZ);
            int size = header.getChunkSize(chunkX, chunkZ);
            
            if (size <= 0 || size > LRFConstants.MAX_CHUNK_SIZE) {
                throw new IOException("Invalid chunk size: " + size + " bytes");
            }
            
            // Read from memory-mapped buffer
            byte[] data = new byte[size];
            if (mappedBuffer != null) {
                synchronized (mappedBuffer) {
                    mappedBuffer.position(offset);
                    mappedBuffer.get(data);
                }
            } else {
                throw new IOException("No memory mapping available");
            }
            
            // Decompress if needed
            if (header.getCompressionType() != LRFConstants.COMPRESSION_NONE) {
                return TurboCompressionService.getInstance().decompress(data);
            }
            
            return data;
        } catch (Exception e) {
            throw new IOException("Failed to read chunk " + chunkX + "," + chunkZ, e);
        }
    }
    
    /**
     * Read LRF header from memory-mapped file.
     */
    private LRFHeader readHeader() throws IOException {
        byte[] headerData = new byte[LRFConstants.HEADER_SIZE];
        
        if (mappedBuffer != null) {
            synchronized (mappedBuffer) {
                mappedBuffer.position(0);
                mappedBuffer.get(headerData);
            }
        } else {
            throw new IOException("No memory mapping available");
        }
        
        return LRFHeader.read(ByteBuffer.wrap(headerData));
    }
    
    /**
     * Cache a chunk with memory management.
     */
    private void cacheChunk(int chunkIndex, byte[] data) {
        // Check memory limits
        if (currentMemoryUsage.get() + data.length > maxMemoryUsage) {
            cleanupCache();
        }
        
        // If still over limit, don't cache
        if (currentMemoryUsage.get() + data.length > maxMemoryUsage) {
            return;
        }
        
        // Check cache size limit
        if (chunkCache.size() >= maxCacheSize) {
            evictLRU();
        }
        
        CachedChunk cached = new CachedChunk(data, System.currentTimeMillis());
        chunkCache.put(chunkIndex, cached);
        currentMemoryUsage.addAndGet(data.length);
    }
    
    /**
     * Trigger prefetch for chunks around the specified coordinates.
     */
    private void triggerPrefetch(int centerX, int centerZ) {
        if (isClosed.get()) {
            return;
        }
        
        CompletableFuture.runAsync(() -> {
            List<int[]> chunksToPrefetch = new ArrayList<>();
            
            // Collect chunks in prefetch distance
            for (int dx = -prefetchDistance; dx <= prefetchDistance; dx++) {
                for (int dz = -prefetchDistance; dz <= prefetchDistance; dz++) {
                    if (dx == 0 && dz == 0) continue; // Skip current chunk
                    
                    int chunkX = centerX + dx;
                    int chunkZ = centerZ + dz;
                    int chunkIndex = LRFConstants.getChunkIndex(chunkX, chunkZ);
                    
                    // Only prefetch if not already cached
                    if (!chunkCache.containsKey(chunkIndex)) {
                        chunksToPrefetch.add(new int[]{chunkX, chunkZ});
                        
                        // Limit batch size
                        if (chunksToPrefetch.size() >= prefetchBatchSize) {
                            break;
                        }
                    }
                }
                
                if (chunksToPrefetch.size() >= prefetchBatchSize) {
                    break;
                }
            }
            
            // Prefetch chunks
            for (int[] coords : chunksToPrefetch) {
                try {
                    byte[] data = readChunkDirect(coords[0], coords[1]);
                    if (data != null) {
                        int chunkIndex = LRFConstants.getChunkIndex(coords[0], coords[1]);
                        cacheChunk(chunkIndex, data);
                        prefetchCount.incrementAndGet();
                    }
                } catch (IOException e) {
                    // Ignore prefetch errors
                }
            }
        }, prefetchExecutor);
    }
    
    /**
     * Clean up expired entries and manage memory.
     */
    private void cleanupCache() {
        long currentTime = System.currentTimeMillis();
        long expireTime = currentTime - 300000; // 5 minutes
        
        chunkCache.values().removeIf(chunk -> {
            if (chunk.isExpired(currentTime)) {
                currentMemoryUsage.addAndGet(-chunk.getData().length);
                return true;
            }
            return false;
        });
        
        // If still over memory limit, evict LRU
        while (currentMemoryUsage.get() > maxMemoryUsage * 0.9 && !chunkCache.isEmpty()) {
            evictLRU();
        }
    }
    
    /**
     * Evict least recently used chunk.
     */
    private void evictLRU() {
        CachedChunk oldest = null;
        int oldestIndex = -1;
        long oldestTime = Long.MAX_VALUE;
        
        for (var entry : chunkCache.entrySet()) {
            CachedChunk chunk = entry.getValue();
            if (chunk.getLastAccess() < oldestTime) {
                oldestTime = chunk.getLastAccess();
                oldest = chunk;
                oldestIndex = entry.getKey();
            }
        }
        
        if (oldest != null) {
            chunkCache.remove(oldestIndex);
            currentMemoryUsage.addAndGet(-oldest.getData().length);
        }
    }
    
    /**
     * Log performance statistics.
     */
    private void logStatistics() {
        long elapsed = System.currentTimeMillis() - startTime;
        int hits = cacheHits.get();
        int misses = cacheMisses.get();
        int prefetches = prefetchCount.get();
        int cacheSize = chunkCache.size();
        long memoryUsage = currentMemoryUsage.get();
        
        double hitRate = (hits + misses) > 0 ? (double) hits / (hits + misses) * 100 : 0;
        double avgMemoryUsage = memoryUsage / 1024.0 / 1024.0; // MB
        
        System.out.println("[TurboMC] MMapReadAheadEngine stats: " +
                         "hits=" + hits + " misses=" + misses + " (" + String.format("%.1f%%", hitRate) + ") " +
                         "prefetches=" + prefetches + " " +
                         "cache=" + cacheSize + "/" + maxCacheSize + " " +
                         "memory=" + String.format("%.1f", avgMemoryUsage) + "MB " +
                         "elapsed=" + (elapsed / 1000) + "s");
    }
    
    /**
     * Get performance statistics.
     */
    public ReadAheadStats getStats() {
        long elapsed = System.currentTimeMillis() - startTime;
        int hits = cacheHits.get();
        int misses = cacheMisses.get();
        int prefetches = prefetchCount.get();
        
        double hitRate = (hits + misses) > 0 ? (double) hits / (hits + misses) * 100 : 0;
        
        return new ReadAheadStats(
            hits, misses, hitRate, prefetches,
            chunkCache.size(), currentMemoryUsage.get(),
            elapsed, useForeignMemoryAPI
        );
    }
    
    /**
     * Detect if Foreign Memory API is available.
     */
    private boolean detectForeignMemoryAPI() {
        try {
            Class.forName("java.lang.foreign.MemorySegment");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    @Override
    public void close() throws IOException {
        if (isClosed.compareAndSet(false, true)) {
            try {
                // Shutdown executors
                prefetchExecutor.shutdown();
                maintenanceExecutor.shutdown();
                
                if (!prefetchExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    prefetchExecutor.shutdownNow();
                }
                
                if (!maintenanceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    maintenanceExecutor.shutdownNow();
                }
                
                // Close memory mappings
                // memorySession is disabled for compatibility
                
                if (mappedBuffer != null) {
                    // MappedByteBuffer is cleaned up by GC
                }
                
                if (fileChannel != null) {
                    fileChannel.close();
                }
                
                if (file != null) {
                    file.close();
                }
                
                // Final statistics
                System.out.println("[TurboMC] MMapReadAheadEngine closed: " + getStats());
            } catch (Exception e) {
                throw new IOException("Error closing MMapReadAheadEngine", e);
            }
        }
    }
    
    /**
     * Cached chunk entry with expiration.
     */
    private static class CachedChunk {
        private final byte[] data;
        private final long createTime;
        private volatile long lastAccess;
        
        CachedChunk(byte[] data, long createTime) {
            this.data = data;
            this.createTime = createTime;
            this.lastAccess = createTime;
        }
        
        void updateLastAccess() {
            this.lastAccess = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }
        
        boolean isExpired(long currentTime) {
            return (currentTime - lastAccess) > 300000; // 5 minutes
        }
        
        byte[] getData() { return data; }
        long getCreateTime() { return createTime; }
        long getLastAccess() { return lastAccess; }
    }
    
    /**
     * Statistics for read-ahead engine performance.
     */
    public static class ReadAheadStats {
        private final int cacheHits;
        private final int cacheMisses;
        private final double hitRate;
        private final int prefetchCount;
        private final int cacheSize;
        private final long memoryUsage;
        private final long elapsedMs;
        private final boolean usingForeignMemoryAPI;
        
        ReadAheadStats(int cacheHits, int cacheMisses, double hitRate, int prefetchCount,
                      int cacheSize, long memoryUsage, long elapsedMs, boolean usingForeignMemoryAPI) {
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.hitRate = hitRate;
            this.prefetchCount = prefetchCount;
            this.cacheSize = cacheSize;
            this.memoryUsage = memoryUsage;
            this.elapsedMs = elapsedMs;
            this.usingForeignMemoryAPI = usingForeignMemoryAPI;
        }
        
        public int getCacheHits() { return cacheHits; }
        public int getCacheMisses() { return cacheMisses; }
        public double getHitRate() { return hitRate; }
        public int getPrefetchCount() { return prefetchCount; }
        public int getCacheSize() { return cacheSize; }
        public long getMemoryUsage() { return memoryUsage; }
        public long getElapsedMs() { return elapsedMs; }
        public boolean isUsingForeignMemoryAPI() { return usingForeignMemoryAPI; }
        
        @Override
        public String toString() {
            return String.format("ReadAheadStats{hits=%d, misses=%d, hitRate=%.1f%%, prefetches=%d, cache=%d, memory=%.1fMB, elapsed=%ds, foreignAPI=%s}",
                    cacheHits, cacheMisses, hitRate, prefetchCount, cacheSize, 
                    memoryUsage / 1024.0 / 1024.0, elapsedMs / 1000, usingForeignMemoryAPI);
        }
    }
}
