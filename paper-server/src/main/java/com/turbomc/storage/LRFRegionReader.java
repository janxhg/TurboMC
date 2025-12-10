package com.turbomc.storage;

import com.turbomc.compression.TurboCompressionService;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads LRF (Linear Region Format) files.
 * Provides efficient sequential and random access to chunks.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class LRFRegionReader implements AutoCloseable {
    
    private final Path filePath;
    private final RandomAccessFile file;
    private final FileChannel channel;
    private final LRFHeader header;
    
    // Performance optimizations
    private final ByteBuffer readBuffer;
    private final java.util.concurrent.ConcurrentHashMap<Integer, byte[]> chunkCache;
    private final AtomicLong cacheHits;
    private final AtomicLong cacheMisses;
    
    // Batch read support
    private final java.util.List<Integer> batchQueue;
    private final Object batchLock;
    
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
        
        // Read and validate header
        ByteBuffer headerBuffer = ByteBuffer.allocate(LRFConstants.HEADER_SIZE);
        channel.read(headerBuffer, 0);
        headerBuffer.flip();
        
        try {
            this.header = LRFHeader.read(headerBuffer);
        } catch (IllegalArgumentException e) {
            close();
            throw new IOException("Invalid LRF file: " + filePath, e);
        }
        
        // Initialize performance components
        this.readBuffer = ByteBuffer.allocate(LRFConstants.STREAM_BUFFER_SIZE);
        this.chunkCache = new java.util.concurrent.ConcurrentHashMap<>();
        this.cacheHits = new AtomicLong(0);
        this.cacheMisses = new AtomicLong(0);
        this.batchQueue = new java.util.ArrayList<>();
        this.batchLock = new Object();
        
        System.out.println("[TurboMC] Opened LRF region: " + filePath.getFileName() + 
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
        byte[] cachedData = chunkCache.get(chunkIndex);
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
        
        // Read with buffer reuse
        byte[] compressedData = readWithBuffer(offset, size);
        
        // Decompress if needed
        byte[] data;
        if (header.getCompressionType() == LRFConstants.COMPRESSION_NONE) {
            data = compressedData;
        } else {
            data = TurboCompressionService.getInstance().decompress(compressedData);
        }
        
        // Cache the result (with size limit)
        if (chunkCache.size() < LRFConstants.CACHE_SIZE) {
            chunkCache.put(chunkIndex, data);
        }
        
        return createChunkEntry(chunkX, chunkZ, data);
    }
    
    /**
     * Helper method to read with buffer reuse.
     */
    private byte[] readWithBuffer(long offset, int size) throws IOException {
        if (size <= readBuffer.capacity()) {
            readBuffer.clear();
            readBuffer.limit(size);
            int bytesRead = channel.read(readBuffer, offset);
            if (bytesRead != size) {
                throw new IOException("Failed to read chunk: expected " + size + 
                                    " bytes, got " + bytesRead);
            }
            readBuffer.flip();
            byte[] result = new byte[size];
            readBuffer.get(result);
            return result;
        } else {
            // Fallback for large chunks
            ByteBuffer buffer = ByteBuffer.allocate(size);
            int bytesRead = channel.read(buffer, offset);
            if (bytesRead != size) {
                throw new IOException("Failed to read chunk: expected " + size + 
                                    " bytes, got " + bytesRead);
            }
            buffer.flip();
            byte[] result = new byte[size];
            buffer.get(result);
            return result;
        }
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
     * Read all chunks in the region with batch optimization.
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
    
    /**
     * Get the file header.
     * 
     * @return LRF header
     */
    public LRFHeader getHeader() {
        return header;
    }
    
    /**
     * Get file path.
     * 
     * @return Path to LRF file
     */
    public Path getFilePath() {
        return filePath;
    }
    
    /**
     * Get file size in bytes.
     * 
     * @return File size
     * @throws IOException if size cannot be determined
     */
    public long getFileSize() throws IOException {
        return channel.size();
    }
    
    /**
     * Get cache statistics.
     */
    public CacheStats getCacheStats() {
        return new CacheStats(cacheHits.get(), cacheMisses.get(), chunkCache.size());
    }
    
    /**
     * Clear chunk cache.
     */
    public void clearCache() {
        chunkCache.clear();
        cacheHits.set(0);
        cacheMisses.set(0);
    }
    
    /**
     * Cache statistics holder.
     */
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
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (file != null) {
                file.close();
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
