package com.turbomc.storage.mmap;

import com.turbomc.storage.lrf.LRFConstants;
import com.turbomc.storage.lrf.LRFHeader;

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
import com.turbomc.storage.optimization.SharedRegionResource;
import java.util.concurrent.atomic.AtomicBoolean;
import com.turbomc.storage.optimization.SharedRegionResource;
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
    private final boolean predictiveEnabled;
    private final int predictionScale;
    private boolean useForeignMemoryAPI;

    // Memory management
    private final AtomicLong currentMemoryUsage;
    private final AtomicInteger cacheHits;
    private final AtomicInteger cacheMisses;
    private final AtomicInteger prefetchCount;
    private final SharedRegionResource sharedResource;
    
    // File mapping
    private RandomAccessFile file;
    private FileChannel fileChannel;
    private MappedByteBuffer mappedBuffer;
    // private MemorySegment memorySegment;
    // private MemorySession memorySession;
    
    // Statistics
    private final long startTime;
    
    // Header cache
    private volatile LRFHeader cachedHeader;
    private long lastHeaderRefresh;
    private long lastFileModified;
    private final Object headerLock = new Object();

    public MMapReadAheadEngine(SharedRegionResource resource) throws IOException {
        this(resource, 
             512,  // max cache size (chunks)
             8,    // prefetch distance (chunks)
             32,   // prefetch batch size
             256 * 1024 * 1024, // 256MB max memory
             true, // predictive enabled
             12);   // prediction scale (increased for speed 10)
    }
    
    public MMapReadAheadEngine(Path regionPath, int maxCacheSize, int prefetchDistance,
                              int prefetchBatchSize, long maxMemoryUsage) throws IOException {
        this(new SharedRegionResource(regionPath), maxCacheSize, prefetchDistance, prefetchBatchSize, maxMemoryUsage, true, 6);
    }

    public MMapReadAheadEngine(SharedRegionResource resource, int maxCacheSize, int prefetchDistance,
                              int prefetchBatchSize, long maxMemoryUsage, 
                              boolean predictiveEnabled, int predictionScale) throws IOException {
        this(resource, maxCacheSize, prefetchDistance, prefetchBatchSize, maxMemoryUsage, predictiveEnabled, predictionScale, null);
        
        System.out.println("[TurboMC] MMapReadAheadEngine initialized: " + regionPath.getFileName() +
                         " (cache: " + maxCacheSize + " chunks, " +
                         "prefetch: " + prefetchDistance + " chunks, " +
                         "predictive: " + predictiveEnabled + " (" + predictionScale + "), " +
                         "memory: " + (maxMemoryUsage / 1024 / 1024) + "MB, " +
                         "foreign-api: " + useForeignMemoryAPI + ")" + 
                         (sharedResource != null ? " [SHARED]" : ""));
    }
    
    // New constructor with executor
    public MMapReadAheadEngine(SharedRegionResource resource, int maxCacheSize, int prefetchDistance,
                              int prefetchBatchSize, long maxMemoryUsage, 
                              boolean predictiveEnabled, int predictionScale, 
                              ExecutorService prefetchExecutor) throws IOException {
        this.regionPath = resource.getPath();
        this.sharedResource = resource;
        this.maxCacheSize = maxCacheSize;
        this.prefetchDistance = prefetchDistance;
        this.prefetchBatchSize = prefetchBatchSize;
        this.maxMemoryUsage = maxMemoryUsage;
        this.predictiveEnabled = predictiveEnabled;
        this.predictionScale = predictionScale;
        this.useForeignMemoryAPI = detectForeignMemoryAPI();
        
        if (prefetchExecutor != null) {
            this.prefetchExecutor = prefetchExecutor;
        } else {
            this.prefetchExecutor = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "MMapReadAhead-Prefetch-" + System.currentTimeMillis());
                t.setDaemon(true);
                return t;
            });
        }
        
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
        
        resource.acquire();
        initializeFileMapping();
        startMaintenanceTasks();
    }
        

    
    /**
     * Initialize memory-mapped file access.
     */
    private void initializeFileMapping() throws IOException {
        this.file = null; // Controlled by sharedResource
        this.fileChannel = sharedResource.getChannel();
        
        // Use shared mapping if available, or create one
        this.mappedBuffer = sharedResource.getOrCreateMappedBuffer(fileChannel.size());
        
        if (mappedBuffer != null) {
            mappedBuffer.load(); // Preload for NVMe
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
            
            // PROACTIVE: Trigger prefetch on hits too! 
            // This ensures we keep moving ahead of the player even if they hit cached chunks.
            triggerPrefetch(chunkX, chunkZ);
            
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
            if (mappedBuffer != null && offset + size <= mappedBuffer.limit()) {
                try {
                    // Optimized concurrent access - absolute get available in Java 13+ (Minecraft 1.21 uses Java 21)
                    mappedBuffer.get(offset, data);
                } catch (Exception e) {
                    // Fallback to standard I/O if mmap access fails (rare race)
                    ByteBuffer buffer = ByteBuffer.wrap(data);
                    fileChannel.read(buffer, offset);
                }
            } else {
                // Fallback to standard I/O if not mapped or beyond mapping limit (file growth)
                int bytesRead = 0;
                int retries = 0;
                while (bytesRead < size && retries < 3) {
                    ByteBuffer buffer = ByteBuffer.wrap(data, bytesRead, size - bytesRead);
                    int read = fileChannel.read(buffer, offset + bytesRead);
                    if (read <= 0) {
                        if (retries < 2) {
                            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                        }
                        retries++;
                        continue;
                    }
                    bytesRead += read;
                }
                
                if (bytesRead < size) {
                    return null; // Avoid processing partial data
                }
            }
            
            // Parse Local Chunk Header (5 bytes)
            // [Length: 4 bytes] [CompressionType: 1 byte] [Payload: N bytes]
            // Note: header.getChunkSize() returns aligned sector size (4KB), 
            // so we MUST read the actual length from the local header.
            int actualLength = ByteBuffer.wrap(data, 0, 4).getInt();
            int localType = data[4];
            
            // Validate length
            if (actualLength <= 0 || actualLength > size) {
                 // Corrupt data or race condition?
                 // If actualLength > size (allocated sector space), it's definitely corrupt.
                 // But for now, let's respect the local header if within bounds.
                 if (actualLength > size) {
                     throw new IOException("Chunk length (" + actualLength + ") exceeds allocated sector size (" + size + ")");
                 }
            }
            
            // Extract payload
            // actualLength includes the 4 length bytes, so payload start is at 5.
            // Payload size = actualLength - 4 (length bytes) - 1 (type byte) = actualLength - 5.
            int payloadSize = actualLength - 5;
            
            if (payloadSize < 0) {
                 throw new IOException("Invalid chunk payload size: " + payloadSize);
            }
            
            byte[] payload = new byte[payloadSize];
            System.arraycopy(data, 5, payload, 0, payloadSize);
            
            // Decompress if needed (Use local type as authority)
            if (localType != LRFConstants.COMPRESSION_NONE) {
                return TurboCompressionService.getInstance().decompress(payload);
            }
            
            return payload;
        } catch (Exception e) {
            throw new IOException("Failed to read chunk " + chunkX + "," + chunkZ, e);
        }
    }
    
    /**
     * Read LRF header from either cache or disk.
     */
    private LRFHeader readHeader() throws IOException {
        if (sharedResource != null) {
            return sharedResource.getHeader();
        }
        
        long currentModified;
        // ... (rest of old logic for non-shared path)
        try {
            currentModified = java.nio.file.Files.getLastModifiedTime(regionPath).toMillis();
        } catch (IOException e) {
            currentModified = System.currentTimeMillis();
        }
        
        LRFHeader header = cachedHeader;
        if (header != null && currentModified <= lastFileModified && 
            (System.currentTimeMillis() - lastHeaderRefresh < 5000)) {
            return header;
        }
        
        // Use sharedResource lock if possible, or local
        Object lock = (sharedResource != null) ? sharedResource : this;
        synchronized (lock) {
            // Check again inside lock
            header = cachedHeader;
            if (header != null && currentModified <= lastFileModified && 
                (System.currentTimeMillis() - lastHeaderRefresh < 5000)) {
                return header;
            }
            
            byte[] headerData = new byte[LRFConstants.HEADER_SIZE];
            ByteBuffer headerBuffer = ByteBuffer.wrap(headerData);
            
            int read = fileChannel.read(headerBuffer, 0);
            if (read < LRFConstants.HEADER_SIZE) {
                if (mappedBuffer != null) {
                    mappedBuffer.get(0, headerData);
                } else {
                    throw new IOException("Failed to read LRF header from " + regionPath);
                }
            }
            
            header = LRFHeader.read(ByteBuffer.wrap(headerData));
            cachedHeader = header;
            lastFileModified = currentModified;
            lastHeaderRefresh = System.currentTimeMillis();
            return header;
        }
    }
    
    // Proactive movement prediction fields
    private final AtomicInteger lastAccessX = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger lastAccessZ = new AtomicInteger(Integer.MIN_VALUE);
    
    /**
     * Cache a chunk with memory management.
     */
    private void cacheChunk(int chunkIndex, byte[] data) {
        // ... (existing cache logic)
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
     * Implements "Momentum-Aware" prefetching.
     */
    private void triggerPrefetch(int centerX, int centerZ) {
        if (isClosed.get()) {
            return;
        }
        
        // Update vector calculation
        int lastX = lastAccessX.getAndSet(centerX);
        int lastZ = lastAccessZ.getAndSet(centerZ);
        
        int velX = 0;
        int velZ = 0;
        
        // Calculate velocity if this isn't the first access and it's nearby (not teleport)
        // High Speed Support: Increased threshold from 2 to 10 chunks to allow flyspeed 10
        // Calculate velocity if this isn't the first access and it's nearby (not teleport)
        // High Speed Support: Increased threshold from 2 to 10 chunks to allow flyspeed 10
        if (lastX != Integer.MIN_VALUE && Math.abs(centerX - lastX) <= 12 && Math.abs(centerZ - lastZ) <= 12) {
             velX = centerX - lastX;
             velZ = centerZ - lastZ;
        }
        
        final int fVelX = velX;
        final int fVelZ = velZ;
        
        // Throttling: If we are already prefetching many chunks for this region, skip
        if (prefetchCount.get() > prefetchBatchSize * 4) {
             // Throttled (avoid queue explosion)
             // But we allow it if velocity is very high
             if (Math.abs(fVelX) < 2 && Math.abs(fVelZ) < 2) return;
        }
        
        CompletableFuture.runAsync(() -> {
            List<int[]> chunksToPrefetch = new ArrayList<>();
            
            boolean isFastTravel = Math.abs(fVelX) >= 2 || Math.abs(fVelZ) >= 2;
            
            // 1. Extended Vector Prefetch (PRIORITY FOR HIGH SPEED)
            if (predictiveEnabled && (fVelX != 0 || fVelZ != 0)) {
                 // Dynamic scale based on velocity
                 int dynamicScale = predictionScale;
                 if (isFastTravel) {
                      dynamicScale = predictionScale * 2; // Up to 24 chunks ahead base
                      if (Math.abs(fVelX) > 4 || Math.abs(fVelZ) > 4) {
                           dynamicScale = predictionScale * 4; // Up to 48 chunks ahead for speed 10
                      }
                 }
                 
                 for (int k = 1; k <= dynamicScale; k++) {
                      int lookAheadX = centerX + (fVelX * k);
                      int lookAheadZ = centerZ + (fVelZ * k);
                      
                      // Safety check: Stay within region bounds if possible
                      int idx = LRFConstants.getChunkIndex(lookAheadX & 31, lookAheadZ & 31);
                      if (!chunkCache.containsKey(idx)) {
                           chunksToPrefetch.add(new int[]{lookAheadX, lookAheadZ});
                           if (chunksToPrefetch.size() >= prefetchBatchSize) break;
                      }
                 }
            }
            
            // Update pending count BEFORE loop
            prefetchCount.addAndGet(chunksToPrefetch.size());
            
            // 2. Standard "Spatial" Prefetch (Radius) - ONLY IF NOT FILLING BUDGET WITH VECTOR
            if (chunksToPrefetch.size() < prefetchBatchSize) {
                for (int dx = -prefetchDistance; dx <= prefetchDistance; dx++) {
                    for (int dz = -prefetchDistance; dz <= prefetchDistance; dz++) {
                        if (dx == 0 && dz == 0) continue; 
                        
                        int chunkX = centerX + dx;
                        int chunkZ = centerZ + dz;
                        
                        // Directional Bias: Skip chunks BEHIND us if moving fast
                        if (isFastTravel) {
                             // Using dot product to determine "behind"
                             if (dx * fVelX + dz * fVelZ < 0) {
                                  // This chunk is behind the movement vector
                                  if (Math.abs(dx) > 1 || Math.abs(dz) > 1) {
                                       continue; 
                                  }
                             }
                        }
                        
                        int chunkIndex = LRFConstants.getChunkIndex(chunkX & 31, chunkZ & 31);
                        if (!chunkCache.containsKey(chunkIndex)) {
                            chunksToPrefetch.add(new int[]{chunkX, chunkZ});
                            if (chunksToPrefetch.size() >= prefetchBatchSize) break;
                        }
                    }
                    if (chunksToPrefetch.size() >= prefetchBatchSize) break;
                }
            }
            // Prefetch chunks
            for (int[] coords : chunksToPrefetch) {
                // Stay within same region for THIS engine
                if ((coords[0] >> 5) != (centerX >> 5) || (coords[1] >> 5) != (centerZ >> 5)) {
                     prefetchCount.decrementAndGet();
                     continue; 
                }
                
                try {
                    byte[] data = readChunkDirect(coords[0], coords[1]);
                    if (data != null) {
                        int chunkIndex = LRFConstants.getChunkIndex(coords[0] & 31, coords[1] & 31);
                        cacheChunk(chunkIndex, data);
                    }
                } catch (IOException e) {
                    // Ignore prefetch errors
                } finally {
                    prefetchCount.decrementAndGet();
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
                
                if (sharedResource != null) {
                    sharedResource.close();
                } else {
                    if (fileChannel != null && fileChannel.isOpen()) {
                        fileChannel.close();
                    }
                    if (file != null) {
                        file.close();
                    }
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

    /**
     * Unmaps a direct buffer using Java's Unsafe/Foreign API (depending on version).
     */
    private void cleanBuffer(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) return;
        
        try {
            // Java 9+ approach using Unsafe for cleaning
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            
            java.lang.reflect.Method invokeCleaner = sun.misc.Unsafe.class.getMethod("invokeCleaner", ByteBuffer.class);
            invokeCleaner.invoke(unsafe, buffer);
        } catch (Exception e) {
             // Java 8 fallback
             try {
                 java.lang.reflect.Method cleanerMethod = buffer.getClass().getMethod("cleaner");
                 cleanerMethod.setAccessible(true);
                 Object cleaner = cleanerMethod.invoke(buffer);
                 if (cleaner != null) {
                     java.lang.reflect.Method cleanMethod = cleaner.getClass().getMethod("clean");
                     cleanMethod.setAccessible(true);
                     cleanMethod.invoke(cleaner);
                 }
             } catch (Exception e2) {
                 // Nothing else we can do
             }
        }
    }
}
