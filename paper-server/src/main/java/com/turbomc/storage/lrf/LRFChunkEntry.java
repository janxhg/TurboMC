package com.turbomc.storage.lrf;

/**
 * Represents a single chunk entry in an LRF file.
 * Contains chunk coordinates, data, and metadata.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class LRFChunkEntry {
    
    private final int chunkX;
    private final int chunkZ;
    private final byte[] data;
    private final long timestamp;
    
    /**
     * Create a new chunk entry.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param data Chunk NBT data (already compressed)
     * @param timestamp Last modification time (Unix timestamp in seconds)
     */
    public LRFChunkEntry(int chunkX, int chunkZ, byte[] data, long timestamp) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.data = data;
        this.timestamp = timestamp;
    }
    
    /**
     * Create a chunk entry with current timestamp.
     */
    public LRFChunkEntry(int chunkX, int chunkZ, byte[] data) {
        this(chunkX, chunkZ, data, System.currentTimeMillis() / 1000L);
    }
    
    /**
     * Get local chunk X coordinate within region (0-31).
     */
    public int getLocalX() {
        return chunkX & LRFConstants.CHUNK_X_MASK;
    }
    
    /**
     * Get local chunk Z coordinate within region (0-31).
     */
    public int getLocalZ() {
        return chunkZ & LRFConstants.CHUNK_Z_MASK;
    }
    
    /**
     * Get chunk index in region (0-1023).
     */
    public int getIndex() {
        return LRFConstants.getChunkIndex(chunkX, chunkZ);
    }
    
    /**
     * Get chunk data size.
     */
    public int getSize() {
        return data != null ? data.length : 0;
    }
    
    /**
     * Check if chunk is empty.
     */
    public boolean isEmpty() {
        return data == null || data.length == 0;
    }
    
    // Getters
    
    public int getChunkX() {
        return chunkX;
    }
    
    public int getChunkZ() {
        return chunkZ;
    }
    
    public byte[] getData() {
        return data;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * Get compressed size of chunk data.
     */
    public int getCompressedSize() {
        return getSize();
    }
    
    /**
     * Get estimated uncompressed size (approximation for inspection).
     */
    public int getUncompressedSize() {
        // Rough estimation: compressed data is typically 30-70% of original
        // For inspection purposes, we'll use a 50% expansion estimate
        return data != null ? (int)(data.length * 2.0) : 0;
    }
    
    /**
     * Get block count (estimated for inspection).
     */
    public int getBlockCount() {
        // Standard chunk has 16x16x384 blocks = 98,304 blocks maximum
        // For inspection, we'll estimate based on data size
        if (data == null || data.length == 0) return 0;
        
        // Rough estimation: larger data = more blocks
        // This is just for visualization purposes
        int estimatedBlocks = Math.min(98304, data.length * 10);
        return estimatedBlocks;
    }
    
    /**
     * Get compression type (from header or default).
     */
    public String getCompressionType() {
        // In a real implementation, this would be stored in the chunk entry
        // For now, return default compression type
        return "LZ4";
    }
    
    /**
     * Get chunk Y coordinate (for 3D positioning).
     */
    public int getY() {
        // LRF doesn't store Y coordinate, return 0 as default
        return 0;
    }
    
    @Override
    public String toString() {
        return String.format("LRFChunkEntry{x=%d, z=%d, size=%d bytes, timestamp=%d}",
                chunkX, chunkZ, getSize(), timestamp);
    }
}
