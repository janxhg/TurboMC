package com.turbomc.storage;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Represents the 256-byte header of an LRF file.
 * 
 * Header structure:
 * - Magic bytes (9 bytes): "TURBO_LRF"
 * - Version (4 bytes): Format version number
 * - Chunk count (4 bytes): Number of chunks in this region
 * - Compression type (4 bytes): Compression algorithm used
 * - Offsets table (remaining bytes): Chunk offset and size entries
 * 
 * Each offset entry is 4 bytes:
 * - 3 bytes for offset (supports up to 16MB file)
 * - 1 byte for size in 4KB sectors (max 1MB per chunk)
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class LRFHeader {
    
    private final int version;
    private final int chunkCount;
    private final int compressionType;
    private final int[] offsets;  // Offset in bytes
    private final int[] sizes;    // Size in bytes
    
    /**
     * Create a new LRF header.
     * 
     * @param version Format version
     * @param chunkCount Number of chunks
     * @param compressionType Compression algorithm
     */
    public LRFHeader(int version, int chunkCount, int compressionType) {
        this.version = version;
        this.chunkCount = chunkCount;
        this.compressionType = compressionType;
        this.offsets = new int[LRFConstants.CHUNKS_PER_REGION];
        this.sizes = new int[LRFConstants.CHUNKS_PER_REGION];
        Arrays.fill(offsets, 0);
        Arrays.fill(sizes, 0);
    }
    
    /**
     * Create header from existing data arrays.
     */
    public LRFHeader(int version, int chunkCount, int compressionType, int[] offsets, int[] sizes) {
        this.version = version;
        this.chunkCount = chunkCount;
        this.compressionType = compressionType;
        this.offsets = offsets;
        this.sizes = sizes;
    }
    
    /**
     * Read header from a ByteBuffer.
     * 
     * @param buffer ByteBuffer positioned at start of header
     * @return Parsed header
     * @throws IllegalArgumentException if magic bytes don't match
     */
    public static LRFHeader read(ByteBuffer buffer) {
        // Verify magic bytes
        byte[] magic = new byte[LRFConstants.MAGIC_LENGTH];
        buffer.get(magic);
        if (!Arrays.equals(magic, LRFConstants.MAGIC_BYTES)) {
            throw new IllegalArgumentException("Invalid LRF file: magic bytes mismatch");
        }
        
        // Read metadata
        int version = buffer.getInt();
        int chunkCount = buffer.getInt();
        int compressionType = buffer.getInt();
        
        // Read offset table
        int[] offsets = new int[LRFConstants.CHUNKS_PER_REGION];
        int[] sizes = new int[LRFConstants.CHUNKS_PER_REGION];
        
        for (int i = 0; i < LRFConstants.CHUNKS_PER_REGION; i++) {
            int entry = buffer.getInt();
            // Upper 24 bits = offset in 256-byte sectors
            // Lower 8 bits = size in 4KB sectors
            int offsetSectors = (entry >>> 8) & 0xFFFFFF;
            int sizeSectors = entry & 0xFF;
            
            offsets[i] = offsetSectors * 256;
            sizes[i] = sizeSectors * 4096;
        }
        
        // Skip any remaining header padding
        int remaining = LRFConstants.HEADER_SIZE - buffer.position();
        if (remaining > 0) {
            buffer.position(buffer.position() + remaining);
        }
        
        return new LRFHeader(version, chunkCount, compressionType, offsets, sizes);
    }
    
    /**
     * Write header to a ByteBuffer.
     * 
     * @param buffer ByteBuffer to write to
     */
    public void write(ByteBuffer buffer) {
        // Write magic bytes
        buffer.put(LRFConstants.MAGIC_BYTES);
        
        // Write metadata
        buffer.putInt(version);
        buffer.putInt(chunkCount);
        buffer.putInt(compressionType);
        
        // Write offset table
        for (int i = 0; i < LRFConstants.CHUNKS_PER_REGION; i++) {
            int offsetSectors = offsets[i] / 256;
            int sizeSectors = (sizes[i] + 4095) / 4096; // Round up to 4KB sectors
            
            // Combine into single 32-bit entry
            int entry = (offsetSectors << 8) | (sizeSectors & 0xFF);
            buffer.putInt(entry);
        }
        
        // Pad remaining header to 256 bytes
        while (buffer.position() < LRFConstants.HEADER_SIZE) {
            buffer.put((byte) 0);
        }
    }
    
    /**
     * Set chunk offset and size.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param offset Offset in bytes
     * @param size Size in bytes
     */
    public void setChunkData(int chunkX, int chunkZ, int offset, int size) {
        int index = LRFConstants.getChunkIndex(chunkX, chunkZ);
        offsets[index] = offset;
        sizes[index] = size;
    }
    
    /**
     * Get chunk offset.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Offset in bytes, or 0 if chunk doesn't exist
     */
    public int getChunkOffset(int chunkX, int chunkZ) {
        int index = LRFConstants.getChunkIndex(chunkX, chunkZ);
        return offsets[index];
    }
    
    /**
     * Get chunk size.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Size in bytes, or 0 if chunk doesn't exist
     */
    public int getChunkSize(int chunkX, int chunkZ) {
        int index = LRFConstants.getChunkIndex(chunkX, chunkZ);
        return sizes[index];
    }
    
    /**
     * Check if chunk exists.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return True if chunk exists
     */
    public boolean hasChunk(int chunkX, int chunkZ) {
        return getChunkSize(chunkX, chunkZ) > 0;
    }
    
    // Getters
    
    public int getVersion() {
        return version;
    }
    
    public int getChunkCount() {
        return chunkCount;
    }
    
    public int getCompressionType() {
        return compressionType;
    }
    
    public int[] getOffsets() {
        return offsets;
    }
    
    public int[] getSizes() {
        return sizes;
    }
    
    @Override
    public String toString() {
        return String.format("LRFHeader{version=%d, chunks=%d, compression=%s}",
                version, chunkCount, LRFConstants.getCompressionName(compressionType));
    }
}
