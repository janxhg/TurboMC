package com.turbomc.storage;

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
    
    // Performance optimizations
    // Synchronized cache for raw bytes (decompressed or not, depending on usage)
    private final Int2ObjectMap<byte[]> chunkCache;
    private final Object cacheLock = new Object();
    
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
        
        long fileSize = channel.size();
        
        // Map the entire file strictly read-only
        // Note: For extremely large files > 2GB this might need chunked mapping, 
        // but Minecraft regions are rarely that big (max 32x32 chunks * 1MB = ~1GB).
        this.mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
        
        // Read and validate header (first 256 bytes)
        // Slice a duplicate to avoid modifying position of main buffer if we needed concurrent access locally
        ByteBuffer headerBuffer = mappedBuffer.slice(); 
        headerBuffer.limit(LRFConstants.HEADER_SIZE);
        
        try {
            this.header = LRFHeader.read(headerBuffer);
        } catch (IllegalArgumentException e) {
            close();
            throw new IOException("Invalid LRF file: " + filePath, e);
        }
        
        // Initialize performance components
        this.chunkCache = new Int2ObjectOpenHashMap<>(LRFConstants.CACHE_SIZE);
        this.cacheHits = new AtomicLong(0);
        this.cacheMisses = new AtomicLong(0);
        
        System.out.println("[TurboMC] Opened LRF region (mmap): " + filePath.getFileName() + 
                         " (" + header.getChunkCount() + " chunks, " + 
                         LRFConstants.getCompressionName(header.getCompressionType()) + ")");
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
        
        if (size <= 0 || size > LRFConstants.MAX_CHUNK_SIZE) {
            throw new IOException("Invalid chunk size: " + size + " bytes");
        }
        
        // Zero-copy read from mmap
        // Note: 'size' from header is the full sector aligned size
        byte[] rawSectorData = new byte[size];
        
        // Duplicate buffer to allow thread-safe position changes
        ByteBuffer threadLocalBuffer = mappedBuffer.duplicate();
        threadLocalBuffer.position(offset);
        threadLocalBuffer.get(rawSectorData);
        
        // Parse Length Header (First 4 bytes)
        // LRF v1.1: [Length (4b)][Data...][Timestamp (8b)]
        if (size < 4) return null;
        
        // Manual Int read to avoid ByteBuffer allocation
        int exactLength = ((rawSectorData[0] & 0xFF) << 24) | 
                          ((rawSectorData[1] & 0xFF) << 16) | 
                          ((rawSectorData[2] & 0xFF) << 8) | 
                          (rawSectorData[3] & 0xFF);
                          
        if (exactLength <= 0 || exactLength > size - 4) {
             // If invalid, we cannot trust this chunk.
             // This might happen if reading an old chunk format (v1.0)
             // But treating it as v1.1 is safer than crashing Zstd.
             return null; 
        }
        
        if (exactLength < 8) return null; // Too small for timestamp
        
        // Payload is Data ONLY (excluding timestamp)
        int payloadSize = exactLength - 8;
        byte[] compressedPayload = new byte[payloadSize];
        System.arraycopy(rawSectorData, 4, compressedPayload, 0, payloadSize); // Offset 4
        
        // Decompress if needed
        byte[] data;
        if (header.getCompressionType() == LRFConstants.COMPRESSION_NONE) {
            data = compressedPayload;
        } else {
            data = TurboCompressionService.getInstance().decompress(compressedPayload);
        }
        
        // Cache the result (with size limit simple check)
        synchronized (cacheLock) {
            if (chunkCache.size() < LRFConstants.CACHE_SIZE) {
                chunkCache.put(chunkIndex, data);
            }
        }
        
        return createChunkEntry(chunkX, chunkZ, data);
    }
    
    /**
     * Helper method to create chunk entry.
     */
    private LRFChunkEntry createChunkEntry(int chunkX, int chunkZ, byte[] data) {
        // Timestamp is stored at the end of each chunk (8 bytes)
        long timestamp = 0;
        if (data.length >= 8) {
            ByteBuffer tsBuffer = ByteBuffer.wrap(data, data.length - 8, 8);
            timestamp = tsBuffer.getLong();
        }
        return new LRFChunkEntry(chunkX, chunkZ, data, timestamp);
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
            chunkCache.clear();
        }
        cacheHits.set(0);
        cacheMisses.set(0);
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
            // Critical Fix for Windows: Explicitly unmap the MappedByteBuffer
            if (mappedBuffer != null && mappedBuffer.isDirect()) {
                cleanBuffer(mappedBuffer);
            }
        } finally {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (file != null) {
                file.close();
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
