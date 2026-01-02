package com.turbomc.storage.lrf;

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
    // FIX: Replace synchronized ByteBuffer with lock-free int array
    private final int[] offsetTable; // 1024 entries, 4KB total
    private final boolean[] chunkExists; // 1024 bits for existence check
    
    
    // FIX #8: Use LinkedHashMap with LRU eviction instead of unbounded ConcurrentHashMap
    private static final int MAX_HEADER_CACHE_SIZE = 256;
    private static final java.util.Map<Integer, LRFHeader> headerCache = 
        java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<Integer, LRFHeader>(
            MAX_HEADER_CACHE_SIZE + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Integer, LRFHeader> eldest) {
                return size() > MAX_HEADER_CACHE_SIZE;
            }
        });
    
    /**
     * Create header from compact storage.
     */
    private LRFHeader(int version, int chunkCount, int compressionType, 
                     int[] offsetTable, boolean[] chunkExists) {
        this.version = version;
        this.chunkCount = chunkCount;
        this.compressionType = compressionType;
        this.offsetTable = offsetTable;
        this.chunkExists = chunkExists;
    }
    
    /**
     * Create header from existing data arrays.
     */
    public LRFHeader(int version, int chunkCount, int compressionType, int[] offsets, int[] sizes) {
        this.version = version;
        this.chunkCount = chunkCount;
        this.compressionType = compressionType;
        
        // Compact storage: pack offset/size into 4 bytes each
        this.offsetTable = new int[LRFConstants.CHUNKS_PER_REGION];
        this.chunkExists = new boolean[LRFConstants.CHUNKS_PER_REGION];
        
        for (int i = 0; i < LRFConstants.CHUNKS_PER_REGION; i++) {
            int offsetSectors = offsets[i] / 256;
            int sizeSectors = (sizes[i] + 4095) / 4096;
            offsetTable[i] = (offsetSectors << 8) | (sizeSectors & 0xFF);
            chunkExists[i] = sizes[i] > 0;
        }
    }
    
    /**
     * Create header for streaming mode.
     */
    public LRFHeader(int version, int chunkCount, int compressionType) {
        this.version = version;
        this.chunkCount = chunkCount;
        this.compressionType = compressionType;
        
        // Initialize empty compact storage
        this.offsetTable = new int[LRFConstants.CHUNKS_PER_REGION];
        this.chunkExists = new boolean[LRFConstants.CHUNKS_PER_REGION];
    }
    
    /**
     * Create empty default header.
     */
    public LRFHeader() {
        this(LRFConstants.FORMAT_VERSION, 0, LRFConstants.COMPRESSION_LZ4);
    }
    
    /**
     * Read header from a ByteBuffer with caching support.
     * 
     * @param buffer ByteBuffer positioned at start of header
     * @return Parsed header
     * @throws IllegalArgumentException if magic bytes don't match
     */
    public static LRFHeader read(ByteBuffer buffer) {
        // Verify magic bytes
        byte[] magic = new byte[LRFConstants.MAGIC_LENGTH];
        if (buffer.remaining() < magic.length) {
             throw new IllegalArgumentException("Buffer too small for LRF magic");
        }
        buffer.get(magic);
        if (!Arrays.equals(magic, LRFConstants.MAGIC_BYTES)) {
            throw new IllegalArgumentException("Invalid LRF file: magic bytes mismatch");
        }
        
        // Read metadata
        int version = buffer.getInt();
        int chunkCount = buffer.getInt();
        int compressionType = buffer.getInt();
        
        // Read offset table directly into int array (lock-free)
        int[] offsetTable = new int[LRFConstants.CHUNKS_PER_REGION];
        boolean[] chunkExists = new boolean[LRFConstants.CHUNKS_PER_REGION];
        
        for (int i = 0; i < LRFConstants.CHUNKS_PER_REGION; i++) {
            int entry = buffer.getInt();
            offsetTable[i] = entry;
            int sizeSectors = entry & 0xFF;
            chunkExists[i] = sizeSectors > 0;
        }
        
        // Skip any remaining header padding
        int remaining = LRFConstants.HEADER_SIZE - buffer.position();
        if (remaining > 0) {
            // Check if we can safely skip
            if (buffer.remaining() >= remaining) {
                buffer.position(buffer.position() + remaining);
            }
        }
        
        return new LRFHeader(version, chunkCount, compressionType, offsetTable, chunkExists);
    }
    
    /**
     * Create an empty header for a new file.
     */
    public static LRFHeader createEmpty() {
        return new LRFHeader(LRFConstants.FORMAT_VERSION, 0, LRFConstants.COMPRESSION_LZ4);
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
            int chunkX = i % LRFConstants.REGION_SIZE;
            int chunkZ = i / LRFConstants.REGION_SIZE;
            int offset = getChunkOffset(chunkX, chunkZ);
            int size = getChunkSize(chunkX, chunkZ);
            int offsetSectors = offset / 256;
            int sizeSectors = (size + 4095) / 4096; // Round up to 4KB sectors
            
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
     * Get chunk offset with optimized access.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Offset in bytes, or 0 if chunk doesn't exist
     */
    public int getChunkOffset(int chunkX, int chunkZ) {
        int index = LRFConstants.getChunkIndex(chunkX, chunkZ);
        if (!chunkExists[index]) return 0;
        
        // Lock-free array read (atomic for int)
        int entry = offsetTable[index];
        int offsetSectors = (entry >>> 8) & 0xFFFFFF;
        return offsetSectors * 256;
    }
    
    /**
     * Get chunk size with optimized access.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Size in bytes, or 0 if chunk doesn't exist
     */
    public int getChunkSize(int chunkX, int chunkZ) {
        int index = LRFConstants.getChunkIndex(chunkX, chunkZ);
        if (!chunkExists[index]) return 0;
        
        // Lock-free array read (atomic for int)
        int entry = offsetTable[index];
        int sizeSectors = entry & 0xFF;
        return sizeSectors * 4096;
    }
    
    /**
     * Check if chunk exists - optimized.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return True if chunk exists
     */
    public boolean hasChunk(int chunkX, int chunkZ) {
        int index = LRFConstants.getChunkIndex(chunkX, chunkZ);
        return chunkExists[index];
    }
    
    /**
     * Set chunk offset and size (for writing).
     */
    public void setChunkData(int chunkX, int chunkZ, int offset, int size) {
        int index = LRFConstants.getChunkIndex(chunkX, chunkZ);
        int offsetSectors = offset / 256;
        int sizeSectors = (size + 4095) / 4096; // Round up to sectors
        
        // Lock-free array write (atomic for int)
        offsetTable[index] = (offsetSectors << 8) | (sizeSectors & 0xFF);
        
        // Update existence flag
        chunkExists[index] = size > 0;
    }
    
    /**
     * Get total number of chunks defined in this header.
     */
    public int countChunks() {
        int count = 0;
        for (boolean exists : chunkExists) {
            if (exists) count++;
        }
        return count;
    }
    
    /**
     * Get offsets array for compatibility.
     */
    public int[] getOffsets() {
        int[] offsets = new int[LRFConstants.CHUNKS_PER_REGION];
        for (int i = 0; i < LRFConstants.CHUNKS_PER_REGION; i++) {
            int entry = offsetTable[i];
            int offsetSectors = (entry >>> 8) & 0xFFFFFF;
            offsets[i] = offsetSectors * 256;
        }
        return offsets;
    }
    
    /**
     * Get sizes array for compatibility.
     */
    public int[] getSizes() {
        int[] sizes = new int[LRFConstants.CHUNKS_PER_REGION];
        for (int i = 0; i < LRFConstants.CHUNKS_PER_REGION; i++) {
            int entry = offsetTable[i];
            int sizeSectors = entry & 0xFF;
            sizes[i] = sizeSectors * 4096;
        }
        return sizes;
    }
    
    // Getters - optimized for compact storage
    
    public int getVersion() { return version; }
    public int getChunkCount() { return chunkCount; }
    public int getCompressionType() { return compressionType; }
    
    @Override
    public String toString() {
        return String.format("LRFHeader{version=%d, chunks=%d, compression=%s}",
                version, chunkCount, LRFConstants.getCompressionName(compressionType));
    }
}
