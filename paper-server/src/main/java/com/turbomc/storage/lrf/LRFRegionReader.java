package com.turbomc.storage.lrf;

import com.turbomc.compression.TurboCompressionService;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.turbomc.storage.optimization.SharedRegionResource;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads LRF (Linear Region Format) files.
 * Provides efficient sequential and random access to chunks using Memory Mapping (mmap).
 * 
 * @author TurboMC
 * @version 1.1.0
 */
public class LRFRegionReader implements AutoCloseable {
    
    private final Path filePath;
    private final RandomAccessFile file;
    private final FileChannel channel;
    private final MappedByteBuffer mappedBuffer;
    private final LRFHeader header;
    private final SharedRegionResource sharedResource;
    
    // Performance optimizations
    // Synchronized cache for raw bytes (decompressed or not, depending on usage)
    private final Int2ObjectMap<byte[]> chunkCache;
    private final Object cacheLock = new Object();
    
    // FIXED: Memory usage tracking for cache
    private final AtomicLong currentCacheSize = new AtomicLong(0);
    private static final long MAX_CACHE_MEMORY = 64 * 1024 * 1024; // 64MB limit
    
    // Stats
    private final AtomicLong cacheHits;
    private final AtomicLong cacheMisses;
    
    /**
     * Open an LRF file for reading.
     * 
     * @param filePath Path to .lrf file
     * @throws IOException if file cannot be opened or is invalid
     */
    public LRFRegionReader(Path filePath) throws IOException {
        this.filePath = filePath;
        this.file = new RandomAccessFile(filePath.toFile(), "r");
        this.channel = file.getChannel();
        this.sharedResource = null;
        
        long fileSize = channel.size();
        
        // Respect configuration for Memory Mapping
        boolean mmapEnabled = com.turbomc.config.TurboConfig.getInstance().getBoolean("storage.mmap.enabled", true);
        
        MappedByteBuffer tempBuffer = null;
        if (mmapEnabled && fileSize > 0) {
            try {
                tempBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            } catch (IOException e) {
                System.err.println("[TurboMC] Failed to mmap LRF region: " + e.getMessage());
            }
        }
        this.mappedBuffer = tempBuffer;
        
        // Initialize header
        this.header = readInternalHeader();
        
        // Initialize stats
        this.chunkCache = new Int2ObjectOpenHashMap<>();
        this.cacheHits = new AtomicLong(0);
        this.cacheMisses = new AtomicLong(0);
    }
    
    /**
     * Create reader using a shared resource.
     */
    public LRFRegionReader(SharedRegionResource resource) throws IOException {
        this.filePath = resource.getPath();
        this.file = null;
        this.channel = resource.getChannel();
        this.sharedResource = resource;
        
        resource.acquire();
        
        // Use shared mapping if available
        this.mappedBuffer = resource.getOrCreateMappedBuffer(channel.size());
        
        // Initialize header
        this.header = readInternalHeader();
        
        // Initialize stats
        this.chunkCache = new Int2ObjectOpenHashMap<>();
        this.cacheHits = new AtomicLong(0);
        this.cacheMisses = new AtomicLong(0);
    }
    
    private LRFHeader readInternalHeader() throws IOException {
        if (sharedResource != null) {
            return sharedResource.getHeader();
        }
        
        ByteBuffer headerBuffer = ByteBuffer.allocate(LRFConstants.HEADER_SIZE);
        if (mappedBuffer != null) {
            // Absolute get is concurrent-safe
            mappedBuffer.get(0, headerBuffer.array());
        } else {
            // Absolute read is concurrent-safe
            channel.read(headerBuffer, 0);
        }
        return LRFHeader.read(ByteBuffer.wrap(headerBuffer.array()));
    }
    
    /**
     * Read a specific chunk with caching.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Chunk entry, or null if chunk doesn't exist
     * @throws IOException if read fails
     */
    public LRFChunkEntry readChunk(int chunkX, int chunkZ) throws IOException {
        int chunkIndex = LRFConstants.getChunkIndex(chunkX, chunkZ);
        
        // Check cache first
        byte[] cachedData;
        synchronized (cacheLock) {
            cachedData = chunkCache.get(chunkIndex);
        }
        
        if (cachedData != null) {
            cacheHits.incrementAndGet();
            return createChunkEntry(chunkX, chunkZ, cachedData);
        }
        
        cacheMisses.incrementAndGet();
        
        if (!header.hasChunk(chunkX, chunkZ)) {
            return null;
        }
        
        int offset = header.getChunkOffset(chunkX, chunkZ);
        int size = header.getChunkSize(chunkX, chunkZ);
        
        // FIX #9: Better error messages
        if (size <= 0) {
            if (System.getProperty("turbomc.debug") != null) {
                System.err.println("[TurboMC][LRF][DEBUG] Invalid chunk size " + size + " for (" + chunkX + "," + chunkZ + ")");
            }
            return null;
        }
        
        if (size > LRFConstants.MAX_CHUNK_SIZE) {
            System.err.println("[TurboMC][LRF][WARN] Chunk (" + chunkX + "," + chunkZ + ") size " + size + 
                " exceeds max " + LRFConstants.MAX_CHUNK_SIZE + ", possibly corrupt");
            return null;
        }
        
        byte[] rawSectorData = new byte[size];
        
        if (mappedBuffer != null && offset + size <= mappedBuffer.limit()) {
            // Zero-copy read from mmap
            try {
                // Optimized concurrent access - absolute get is naturally thread-safe
                mappedBuffer.get(offset, rawSectorData);
            } catch (Exception e) {
                // Fallback to standard I/O if mmap access fails
                ByteBuffer readBuffer = ByteBuffer.wrap(rawSectorData);
                channel.read(readBuffer, offset);
            }
        } else {
            // Standard I/O read (or fallback for growth)
            // Absolute channel.read(buffer, position) is naturally thread-safe for reading
            ByteBuffer readBuffer = ByteBuffer.wrap(rawSectorData);
            int bytesRead = channel.read(readBuffer, offset);
            
            if (bytesRead < size) {
                // Retry loop for partial reads (concurrent growth)
                int retries = 0;
                while (bytesRead < size && retries < 3) {
                    try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                    readBuffer = ByteBuffer.wrap(rawSectorData, bytesRead, size - bytesRead);
                    int read = channel.read(readBuffer, offset + bytesRead);
                    if (read > 0) {
                        bytesRead += read;
                    }
                    retries++;
                }
            }
            
            if (bytesRead < size) {
                return null;
            }
        }
        
        // Parse Length Header (First 5 bytes: 4 length + 1 compression type)
        if (size < 5) {
            System.err.println("[TurboMC][LRF][WARN] Chunk (" + chunkX + "," + chunkZ + ") too small: " + size + " bytes");
            return null;
        }
        
        int totalLength = ((rawSectorData[0] & 0xFF) << 24) | 
                          ((rawSectorData[1] & 0xFF) << 16) | 
                          ((rawSectorData[2] & 0xFF) << 8) | 
                          (rawSectorData[3] & 0xFF);
        
        // FIX #11: Read per-chunk compression type
        int chunkCompressionType = rawSectorData[4] & 0xFF;
                           
        if (totalLength <= 5 || totalLength > size || totalLength > LRFConstants.MAX_CHUNK_SIZE) {
            System.err.println("[TurboMC][LRF][WARN] Invalid chunk length " + totalLength + " for (" + chunkX + "," + chunkZ + ")");
            return null; 
        }
        
        int compressedPayloadSize = totalLength - 5;  // Subtract header (4) + compression type (1)
        
        // FIX #10: Use ByteBuffer.slice() to avoid copy
        byte[] compressedPayload;
        if (compressedPayloadSize > 0) {
            ByteBuffer payloadBuffer = ByteBuffer.wrap(rawSectorData, 5, compressedPayloadSize);
            compressedPayload = new byte[compressedPayloadSize];
            payloadBuffer.get(compressedPayload);
        } else {
            compressedPayload = new byte[0];
        }
        
        byte[] data;
        if (chunkCompressionType == LRFConstants.COMPRESSION_NONE) {
            data = compressedPayload;
        } else {
            try {
                data = TurboCompressionService.getInstance().decompress(compressedPayload);
            } catch (Exception e) {
                System.err.println("[TurboMC][LRF][ERROR] Decompression failed for (" + chunkX + "," + chunkZ + "): " + e.getMessage());
                return null;
            }
        }
        
        // FIX: Intelligent batch cache eviction with high watermark
        synchronized (cacheLock) {
            // High watermark strategy - keep cache at 90% to avoid constant eviction
            long highWatermark = (long)(MAX_CACHE_MEMORY * 0.9);
            
            // If adding would exceed watermark, batch evict down to 80%
            if (currentCacheSize.get() + data.length > highWatermark) {
                long targetSize = (long)(MAX_CACHE_MEMORY * 0.8);
                long toFree = (currentCacheSize.get() + data.length) - targetSize;
                
                // Batch eviction - free multiple entries in one pass (MUCH faster!)
                long freed = 0;
                var iterator = chunkCache.int2ObjectEntrySet().iterator();
                
                while (iterator.hasNext() && freed < toFree) {
                    var entry = iterator.next();
                    byte[] evictedData = entry.getValue();
                    iterator.remove();
                    freed += evictedData.length;
                    currentCacheSize.addAndGet(-evictedData.length);
                }
                
                if (freed > 0 && System.getProperty("turbomc.debug") != null) {
                    System.out.println("[TurboMC][LRF][Cache] Batch evicted " + freed / 1024 + 
                        "KB (now at " + currentCacheSize.get() / 1024 + "KB)");
                }
            }
            
            // Now add if there's space
            if (currentCacheSize.get() + data.length <= MAX_CACHE_MEMORY) {
                chunkCache.put(chunkIndex, data);
                currentCacheSize.addAndGet(data.length);
            }
        }
        
        return createChunkEntry(chunkX, chunkZ, data);
    }
    
    /**
     * Helper method to create chunk entry.
     */
    private LRFChunkEntry createChunkEntry(int chunkX, int chunkZ, byte[] data) {
        return new LRFChunkEntry(chunkX, chunkZ, data);
    }

    /**
     * Read all chunks in the region.
     * 
     * @return List of all chunk entries
     * @throws IOException if read fails
     */
    public List<LRFChunkEntry> readAllChunks() throws IOException {
        List<LRFChunkEntry> chunks = new ArrayList<>();
        
        for (int x = 0; x < LRFConstants.REGION_SIZE; x++) {
            for (int z = 0; z < LRFConstants.REGION_SIZE; z++) {
                if (header.hasChunk(x, z)) {
                    LRFChunkEntry chunk = readChunk(x, z);
                    if (chunk != null) {
                        chunks.add(chunk);
                    }
                }
            }
        }
        
        return chunks;
    }
    
    /**
     * Batch read multiple chunks for better performance.
     */
    public List<LRFChunkEntry> readBatch(List<int[]> chunkCoords) throws IOException {
        List<LRFChunkEntry> results = new ArrayList<>();
        
        for (int[] coords : chunkCoords) {
            if (coords.length >= 2) {
                LRFChunkEntry chunk = readChunk(coords[0], coords[1]);
                if (chunk != null) {
                    results.add(chunk);
                }
            }
        }
        
        return results;
    }
    
    /**
     * Check if chunk exists.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return True if chunk exists
     */
    public boolean hasChunk(int chunkX, int chunkZ) {
        return header.hasChunk(chunkX, chunkZ);
    }
    
    public LRFHeader getHeader() {
        return header;
    }
    
    public Path getFilePath() {
        return filePath;
    }
    
    public long getFileSize() throws IOException {
        return channel.size();
    }
    
    /**
     * Get cache statistics.
     */
    public CacheStats getCacheStats() {
        synchronized (cacheLock) {
            return new CacheStats(cacheHits.get(), cacheMisses.get(), chunkCache.size());
        }
    }
    
    /**
     * Clear chunk cache.
     */
    public void clearCache() {
        synchronized (cacheLock) {
            if (chunkCache != null) {
                chunkCache.clear();
            }
        }
        cacheHits.set(0);
        cacheMisses.getAndSet(0);
    }
    
    public static class CacheStats {
        public final long hits;
        public final long misses;
        public final int size;
        
        public CacheStats(long hits, long misses, int size) {
            this.hits = hits;
            this.misses = misses;
            this.size = size;
        }
        
        public double getHitRate() {
            long total = hits + misses;
            return total > 0 ? (double) hits / total * 100 : 0;
        }
        
        @Override
        public String toString() {
            return String.format("CacheStats{hits=%d, misses=%d, hitRate=%.1f%%, size=%d}",
                    hits, misses, getHitRate(), size);
        }
    }
    
    @Override
    public void close() throws IOException {
        try {
            clearCache();
        } finally {
            if (sharedResource != null) {
                sharedResource.close();
            } else {
                // Critical Fix for Windows: Explicitly unmap the MappedByteBuffer
                if (mappedBuffer != null && mappedBuffer.isDirect()) {
                    cleanBuffer(mappedBuffer);
                }
                if (channel != null && channel.isOpen()) {
                    channel.close();
                }
                if (file != null) {
                    file.close();
                }
            }
        }
    }

    /**
     * Unmaps a direct buffer using Java's Unsafe/Foreign API (depending on version).
     * Necessary because MappedByteBuffer normally locks files on Windows until GC.
     */
    private void cleanBuffer(ByteBuffer buffer) {
        if (buffer == null || !buffer.isDirect()) return;
        
        // Java 9+ approach using Unsafe for cleaning
        try {
            // This is a hack but standard practice in high-perf Java IO (Netty/Lucene style)
            // Use reflection to access jdk.internal.ref.Cleaner or sun.misc.Unsafe
            
            // Try sun.misc.Unsafe invokeCleaner (Java 9+)
            java.lang.reflect.Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            
            java.lang.reflect.Method invokeCleaner = sun.misc.Unsafe.class.getMethod("invokeCleaner", ByteBuffer.class);
            invokeCleaner.invoke(unsafe, buffer);
            
        } catch (Exception e) {
             // Fallback or ignore if not possible (e.g. SecurityManager)
             // System.err.println("[TurboMC] Failed to unmap buffer: " + e.getMessage());
             
             // Java 8 fallback (DirectByteBuffer.cleaner().clean())
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

    @Override
    public String toString() {
        return "LRFRegionReader{" +
               "file=" + filePath.getFileName() +
               ", chunks=" + header.getChunkCount() +
               ", compression=" + LRFConstants.getCompressionName(header.getCompressionType()) +
               ", cache=" + getCacheStats() +
               '}';
    }
}
