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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
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
    private final ConcurrentLinkedDeque<Integer> lruOrder;
    private final AtomicBoolean isClosed;
    
    // Configuration
    private final int maxCacheSize;
    private final int prefetchDistance;
    private final int prefetchBatchSize;
    private final long maxMemoryUsage;
    private final boolean predictiveEnabled;
    private final int predictionScale;
    private boolean useForeignMemoryAPI;
    private final int regionX;
    private final int regionZ;
    private final AtomicLong lastUltraScan = new AtomicLong(0);

    // Memory management
    private final AtomicLong currentMemoryUsage;
    private final AtomicInteger cacheHits;
    private final AtomicInteger cacheMisses;
    private final AtomicInteger prefetchCount; // Active prefetches
    private final AtomicInteger totalPrefetchCount = new AtomicInteger(0); // v2.2: Cumulative prefetches
    
    // Adaptive Prefetching v2.1
    private final AtomicInteger recentHits = new AtomicInteger(0);
    private final AtomicInteger recentMisses = new AtomicInteger(0);
    private final AtomicInteger recentPrefetchHits = new AtomicInteger(0);
    private volatile int dynamicLookahead;
    private final SharedRegionResource sharedResource;
    
    // Header caching
    private volatile LRFHeader cachedHeader;
    private volatile long lastFileModified;
    private volatile long lastFileSize;
    private volatile long lastHeaderRefresh;
    private final Object headerLock = new Object();
    
    // File mapping
    private RandomAccessFile file;
    private FileChannel fileChannel;
    private MappedByteBuffer mappedBuffer;
    // private MemorySegment memorySegment;
    // private MemorySession memorySession;
    
    // Statistics
    private final long startTime;
    
    // Intent-based Prediction
    private final com.turbomc.streaming.IntentPredictor intentPredictor;
    private final com.turbomc.storage.lod.LODManager lodManager; // TurboMC - LOD Manager
    private final FlushBarrier flushBarrier; // TurboMC - Synchronization barrier
    
    // FIX: Prefetch spam prevention
    private volatile long lastPrefetchTime = 0;
    private volatile int lastPrefetchX = Integer.MIN_VALUE;
    private volatile int lastPrefetchZ = Integer.MIN_VALUE;

    

    public MMapReadAheadEngine(SharedRegionResource resource) throws IOException {
        this(resource, 
             1024, // max cache size (chunks)
             32,   // prefetch distance (chunks)
             64,   // prefetch batch size
             512 * 1024 * 1024, // 512MB max memory
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
        this.lodManager = com.turbomc.storage.lod.LODManager.getInstance(); // TurboMC
        
        // Extract region coordinates from path (e.g., r.0.0.lrf or r.0.0.mca)
        String fileName = regionPath.getFileName().toString();
        int rx = 0, rz = 0;
        try {
            String[] parts = fileName.split("\\.");
            if (parts.length >= 3) {
                rx = Integer.parseInt(parts[1]);
                rz = Integer.parseInt(parts[2]);
            }
        } catch (Exception e) {
            // Fallback
        }
        this.regionX = rx;
        this.regionZ = rz;
        
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
        this.lruOrder = new ConcurrentLinkedDeque<>();
        this.isClosed = new AtomicBoolean(false);
        this.currentMemoryUsage = new AtomicLong(0);
        this.cacheHits = new AtomicInteger(0);
        this.cacheMisses = new AtomicInteger(0);
        this.prefetchCount = new AtomicInteger(0);
        this.dynamicLookahead = predictionScale;
        this.startTime = System.currentTimeMillis();
        
        // Initialize Intent Predictor if enabled
        if (com.turbomc.config.TurboConfig.getInstance().isStreamingEnabled()) {
            this.intentPredictor = new com.turbomc.streaming.IntentPredictor();
        } else {
            this.intentPredictor = null;
        }
        
        resource.acquire();
        this.flushBarrier = resource.getFlushBarrier();
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
     * Check if a chunk is present in the memory cache.
     * v2.1 Optimization: Non-blocking check for storage status.
     */
    public boolean isCached(int chunkX, int chunkZ) {
        int idx = LRFConstants.getChunkIndex(chunkX, chunkZ);
        CachedChunk cached = chunkCache.get(idx);
        return cached != null && !cached.isExpired();
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
            recentHits.incrementAndGet();
            if (cached.isPrefetched()) {
                recentPrefetchHits.incrementAndGet();
            }
            // Update LRU order
            lruOrder.remove(chunkIndex);
            lruOrder.addLast(chunkIndex);
            
            cached.updateLastAccess();
            
            flushBarrier.beforeRead();
            try {
                // PROACTIVE: Trigger prefetch on hits too! 
                // This ensures we keep moving ahead of the player even if they hit cached chunks.
                triggerPrefetch(chunkX, chunkZ);
                
                return cached.getData();
            } finally {
                flushBarrier.afterRead(null);
            }
        }
        
        recentMisses.incrementAndGet();
        cacheMisses.incrementAndGet();
        
        flushBarrier.beforeRead();
        try {
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
        } finally {
            flushBarrier.afterRead(mappedBuffer);
        }
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
            com.turbomc.util.BufferPool pool = com.turbomc.util.BufferPool.getInstance();
            byte[] data = pool.acquire(size);
            
            try {
                if (mappedBuffer != null && offset + size <= mappedBuffer.limit()) {
                    try {
                        // Optimized concurrent access
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
                
                // Parse Local Chunk Header (5 bytes: 4 length + 1 type)
                int actualLength = ((data[0] & 0xFF) << 24) | 
                                  ((data[1] & 0xFF) << 16) | 
                                  ((data[2] & 0xFF) << 8) | 
                                  (data[3] & 0xFF);
                int localType = data[4] & 0xFF;
                
                // Validate length
                if (actualLength <= 0 || actualLength > size) {
                     if (actualLength > size) {
                         throw new IOException("Chunk length (" + actualLength + ") exceeds allocated sector size (" + size + ")");
                     }
                }
                
                int payloadSize = actualLength - 5;
                if (payloadSize < 0) {
                     throw new IOException("Invalid chunk payload size: " + payloadSize);
                }
                
                byte[] payload = pool.acquire(payloadSize);
                try {
                    System.arraycopy(data, 5, payload, 0, payloadSize);
                    
                    // Decompress if needed
                    if (localType != LRFConstants.COMPRESSION_NONE) {
                        return TurboCompressionService.getInstance().decompress(payload);
                    }
                    
                    // If no compression, we must return a fresh copy because payload belongs to pool
                    byte[] copy = new byte[payloadSize];
                    System.arraycopy(payload, 0, copy, 0, payloadSize);
                    return copy;
                } finally {
                    pool.release(payload);
                }
            } finally {
                pool.release(data);
            }
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
        
        long currentSize = fileChannel.size();
        LRFHeader header = cachedHeader;
        if (header != null && currentModified <= lastFileModified && 
            currentSize == lastFileSize && // Check for growth
            (System.currentTimeMillis() - lastHeaderRefresh < 1000)) {
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
            lastFileSize = currentSize;
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
        cacheChunk(chunkIndex, data, false);
    }
    
    /**
     * Cache a chunk with memory management.
     */
    private void cacheChunk(int chunkIndex, byte[] data, boolean prefetched) {
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
        
        CachedChunk cached = new CachedChunk(data, System.currentTimeMillis(), prefetched);
        if (chunkCache.put(chunkIndex, cached) == null) {
            currentMemoryUsage.addAndGet(data.length);
        }
        lruOrder.remove(chunkIndex);
        lruOrder.addLast(chunkIndex);
    }
    
    /**
     * Trigger prefetch for chunks around the specified coordinates.
     * Implements "Momentum-Aware" prefetching + Intent Prediction.
     */
    private void triggerPrefetch(int centerX, int centerZ) {
        if (isClosed.get()) {
            return;
        }
        
        // FIX: Only prefetch if moved or 1 second passed
        long currentTime = System.currentTimeMillis();
        if (centerX == lastPrefetchX && centerZ == lastPrefetchZ &&
            currentTime - lastPrefetchTime < 1000) {
            return;
        }
        lastPrefetchTime = currentTime;
        lastPrefetchX = centerX;
        lastPrefetchZ = centerZ;
        
        // Update vector calculation (Legacy mechanism for stats/compatibility, kept for now)
        int lastX = lastAccessX.getAndSet(centerX);
        int lastZ = lastAccessZ.getAndSet(centerZ);
        
        // Update Intent Predictor
        if (intentPredictor != null) {
            intentPredictor.update(centerX, centerZ);
        }
        
        // Calculate velocity (approximate)
        int velX = 0;
        int velZ = 0;
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
            Set<Integer> chunksToPrefetchKeys = new HashSet<>();
            List<int[]> chunksToPrefetch = new ArrayList<>();
            
            boolean isFastTravel = Math.abs(fVelX) >= 2 || Math.abs(fVelZ) >= 2;
            
            // 1. Intent-based Prediction (Probability Tunnel) - v2.2
            if (intentPredictor != null && predictiveEnabled) {
                List<int[]> predicted = intentPredictor.predict(centerX, centerZ, dynamicLookahead);
                for (int[] p : predicted) {
                    int chunkIndex = LRFConstants.getChunkIndex(p[0] & 31, p[1] & 31);
                    if (!chunkCache.containsKey(chunkIndex) && chunksToPrefetchKeys.add(chunkIndex)) {
                        chunksToPrefetch.add(p);
                        if (chunksToPrefetch.size() >= prefetchBatchSize) break;
                    }
                }
            }
            
            // 2. Spatial Read-Ahead (Spiral Iterator v2.4.0) - O(R) Scan
            if (chunksToPrefetch.size() < prefetchBatchSize) {
                int x = 0, z = 0, dx = 0, dz = -1;
                int maxStep = (prefetchDistance * 2 + 1) * (prefetchDistance * 2 + 1);
                
                for (int i = 0; i < maxStep; i++) {
                    if (chunksToPrefetch.size() >= prefetchBatchSize) break;
                    
                    if (x != 0 || z != 0) {
                        int chunkX = centerX + x;
                        int chunkZ = centerZ + z;
                        
                        // Directional Bias: Skip chunks BEHIND us if moving fast
                        boolean behind = isFastTravel && (x * fVelX + z * fVelZ < 0);
                        if (!behind || (Math.abs(x) <= 1 && Math.abs(z) <= 1)) {
                            int chunkIndex = LRFConstants.getChunkIndex(chunkX & 31, chunkZ & 31);
                            if (!chunkCache.containsKey(chunkIndex) && chunksToPrefetchKeys.add(chunkIndex)) {
                                chunksToPrefetch.add(new int[]{chunkX, chunkZ});
                            }
                        }
                    }
                    
                    if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                        int temp = dx;
                        dx = -dz;
                        dz = temp;
                    }
                    x += dx;
                    z += dz;
                }
            }
            // Update pending count BEFORE loop (MOVED: Now accounts for BOTH Intent and Spatial chunks)
            int count = chunksToPrefetch.size();
            prefetchCount.addAndGet(count);
            totalPrefetchCount.addAndGet(count);

            // Prefetch chunks
            for (int[] coords : chunksToPrefetch) {
                // Stay within same region for THIS engine
                if ((coords[0] >> 5) != (centerX >> 5) || (coords[1] >> 5) != (centerZ >> 5)) {
                     prefetchCount.decrementAndGet();
                     continue; 
                }
                
                int targetX = coords[0];
                int targetZ = coords[1];

                // TurboMC - Virtualization check
                boolean virtualized = lodManager.shouldVirtualizedPrefetch(centerX, centerZ, targetX, targetZ);
                
                try {
                    // Enforce barrier for prefetch reads too
                    flushBarrier.beforeRead();
                    try {
                        if (virtualized) {
                            // Virtual prefetch (LOD 0/3/4) - Just warm the mapping
                            warmChunk(targetX, targetZ);
                        } else {
                            byte[] data = readChunkDirect(targetX, targetZ);
                            if (data != null) {
                                // Speculative validation before caching (v2.4.1)
                                com.turbomc.storage.optimization.TurboStorageManager.getInstance()
                                    .validateSpeculative(regionPath, targetX, targetZ, data)
                                    .thenAccept(report -> {
                                        if (report.isValid()) {
                                            int chunkIndex = LRFConstants.getChunkIndex(targetX & 31, targetZ & 31);
                                            cacheChunk(chunkIndex, data, true);
                                        }
                                    });
                            }
                        }
                    } finally {
                        flushBarrier.afterRead(mappedBuffer);
                    }
                } catch (IOException e) {
                    // Ignore prefetch errors
                } finally {
                    prefetchCount.decrementAndGet();
                }
            }
        }, prefetchExecutor);

        // 3. Ultra Pre-Chunking (v2.4.0)
        if (lodManager.isUltraEnabled()) {
            long now = System.currentTimeMillis();
            if (now - lastUltraScan.get() > 60000) { // Scan at most once per minute
                int playerChunkX = centerX;
                int playerChunkZ = centerZ;
                
                // Approximate distance to region center (in chunks)
                int myCenterX = (regionX << 5) + 16;
                int myCenterZ = (regionZ << 5) + 16;
                int dist = Math.max(Math.abs(playerChunkX - myCenterX), Math.abs(playerChunkZ - myCenterZ));
                
                if (dist <= lodManager.getUltraRadius() + 32) {
                    lastUltraScan.set(now);
                    runUltraScan();
                }
            }
        }
    }

    /**
     * Performs a background sweep of the entire region to pre-warm OS cache/LOD data.
     */
    private void runUltraScan() {
        CompletableFuture.runAsync(() -> {
            if (isClosed.get()) return;
            
            // Drip-feed the whole region (1024 chunks)
            // We use a local list to avoid holding locks
            List<int[]> allChunks = new ArrayList<>(1024);
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    int chunkX = (regionX << 5) + x;
                    int chunkZ = (regionZ << 5) + z;
                    int chunkIndex = LRFConstants.getChunkIndex(x, z);
                    
                    if (!chunkCache.containsKey(chunkIndex)) {
                        allChunks.add(new int[]{chunkX, chunkZ});
                    }
                }
            }
            
            // Process in small sub-batches to avoid hogging the prefetch executor
            for (int i = 0; i < allChunks.size(); i += 8) {
                if (isClosed.get()) break;
                
                final int start = i;
                final int end = Math.min(i + 8, allChunks.size());
                
                prefetchExecutor.execute(() -> {
                    for (int j = start; j < end; j++) {
                        int[] coords = allChunks.get(j);
                        try {
                            // Ultra pre-chunking is ALWAYS virtualized (LOD 3)
                            // v2.4.1 Fix: Use flushBarrier for synchronization even during ultra-scan
                            flushBarrier.beforeRead();
                            try {
                                warmChunk(coords[0], coords[1]);
                            } finally {
                                flushBarrier.afterRead(mappedBuffer);
                            }
                        } catch (IOException ignored) {}
                    }
                });
                
                // Sleep slightly between sub-batches to stay low priority
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }, prefetchExecutor);
    }
    
    /**
     * "Warms" a chunk by touching its memory page without full decompression.
     * Efficient for LOD/Virtualization prefetching.
     */
    private void warmChunk(int chunkX, int chunkZ) throws IOException {
        // Read LRF header to find where the chunk is
        LRFHeader header = readHeader();
        if (!header.hasChunk(chunkX, chunkZ)) return;
        
        int offset = header.getChunkOffset(chunkX, chunkZ);
        
        // Touch the memory to trigger OS paging
        if (mappedBuffer != null && offset < mappedBuffer.limit()) {
            // Just read one byte. This forces the OS to page-in the block containing the start of the chunk.
            // Since pages are usually 4KB, this likely brings in most of the header.
            // We don't need to read the whole payload to "warm" the file cache.
            mappedBuffer.get(offset);
        } else {
             // Fallback: Read 1 byte from file channel
             ByteBuffer dummy = ByteBuffer.allocate(1);
             fileChannel.read(dummy, offset);
        }
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
        Integer oldestIndex = lruOrder.pollFirst();
        if (oldestIndex != null) {
            CachedChunk removed = chunkCache.remove(oldestIndex);
            if (removed != null) {
                currentMemoryUsage.addAndGet(-removed.getData().length);
            }
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
                         "p_hits=" + recentPrefetchHits.get() + " lookahead=" + dynamicLookahead + " " +
                         "prefetch=" + prefetches + " " +
                         "cache=" + cacheSize + "/" + maxCacheSize + " " +
                         "memory=" + String.format("%.1f", avgMemoryUsage) + "MB " +
                         "elapsed=" + (elapsed / 1000) + "s");
        
        // Reset recent counters
        recentHits.set(0);
        recentMisses.set(0);
        recentPrefetchHits.set(0);
    }
    
    /**
     * Controller (v2.1): Adjust lookahead based on prefetch efficiency and demand.
     */
    private void adjustDynamicLookahead() {
        int rHits = recentHits.get();
        int rMisses = recentMisses.get();
        int rPrefetchHits = recentPrefetchHits.get();
        int total = rHits + rMisses;
        
        if (total < 10) return; // Wait for sample size
        
        double hitRate = (double) rHits / total;
        double efficiency = rHits > 0 ? (double) rPrefetchHits / rHits : 0;
        
        // If we have misses despite prefetching, increase lookahead (Scale up)
        if (hitRate < 0.8 && dynamicLookahead < predictionScale * 2) {
            dynamicLookahead++;
        } 
        // If we are hitting almost everything but efficiency is low (wasting IO on chunks we don't enter), scale down
        else if (hitRate > 0.95 && efficiency < 0.4 && dynamicLookahead > Math.max(2, predictionScale / 2)) {
            dynamicLookahead--;
        }
    }
    
    /**
     * Get total cache hits.
     */
    public int getCacheHits() {
        return cacheHits.get();
    }
    
    /**
     * Get total cache misses.
     */
    public int getCacheMisses() {
        return cacheMisses.get();
    }

    /**
     * Get performance statistics.
     */
    public ReadAheadStats getStats() {
        long elapsed = System.currentTimeMillis() - startTime;
        int hits = cacheHits.get();
        int misses = cacheMisses.get();
        int prefetches = totalPrefetchCount.get();
        
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
                    if (mappedBuffer != null) {
                        cleanBuffer(mappedBuffer);
                        mappedBuffer = null;
                    }
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
        private final boolean prefetched;
        private volatile long lastAccess;
        
        CachedChunk(byte[] data, long createTime, boolean prefetched) {
            this.data = data;
            this.createTime = createTime;
            this.prefetched = prefetched;
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
        
        boolean isPrefetched() { return prefetched; }
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
