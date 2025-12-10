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
    // Optimized: Use compact offset/size storage instead of full arrays
    private final ByteBuffer offsetTable; // 228 bytes compact storage
    private final boolean[] chunkExists; // 1024 bits for existence check
    
    // Cache for frequently accessed chunks
    private static final java.util.Map<Integer, LRFHeader> headerCache = 
        new java.util.concurrent.ConcurrentHashMap<>(LRFConstants.CACHE_SIZE);
    
    /**
     * Create header from compact storage.
     */
    private LRFHeader(int version, int chunkCount, int compressionType, 
                     ByteBuffer offsetTable, boolean[] chunkExists) {
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
        this.offsetTable = ByteBuffer.allocate(LRFConstants.OFFSETS_TABLE_SIZE);
        this.chunkExists = new boolean[LRFConstants.CHUNKS_PER_REGION];
        
        for (int i = 0; i < LRFConstants.CHUNKS_PER_REGION; i++) {
            int offsetSectors = offsets[i] / 256;
            int sizeSectors = (sizes[i] + 4095) / 4096;
            int entry = (offsetSectors << 8) | (sizeSectors & 0xFF);
            offsetTable.putInt(entry);
            chunkExists[i] = sizes[i] > 0;
        }
        
        offsetTable.rewind();
    }
    
    /**
     * Create header for streaming mode.
     */
    public LRFHeader(int version, int chunkCount, int compressionType) {
        this.version = version;
        this.chunkCount = chunkCount;
        this.compressionType = compressionType;
        
        // Initialize empty compact storage
        this.offsetTable = ByteBuffer.allocate(LRFConstants.OFFSETS_TABLE_SIZE);
        this.chunkExists = new boolean[LRFConstants.CHUNKS_PER_REGION];
        
        offsetTable.rewind();
    }
    
    /**
     * Read header from a ByteBuffer with caching support.
     * 
     * @param buffer ByteBuffer positioned at start of header
     * @return Parsed header
     * @throws IllegalArgumentException if magic bytes don't match
     */
    public static LRFHeader read(ByteBuffer buffer) {
        // Create cache key from buffer content hash
        int bufferHash = java.util.Arrays.hashCode(Arrays.copyOf(buffer.array(), Math.min(buffer.limit(), 64)));
        
        // Check cache first
        LRFHeader cached = headerCache.get(bufferHash);
        if (cached != null) {
            return cached;
        }
        
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
        
        // Read offset table into compact storage
        ByteBuffer offsetTable = ByteBuffer.allocate(LRFConstants.OFFSETS_TABLE_SIZE);
        buffer.get(offsetTable.array());
        
        // Build chunk existence array
        boolean[] chunkExists = new boolean[LRFConstants.CHUNKS_PER_REGION];
        offsetTable.rewind();
        for (int i = 0; i < LRFConstants.CHUNKS_PER_REGION; i++) {
            int entry = offsetTable.getInt();
            int sizeSectors = entry & 0xFF;
            chunkExists[i] = sizeSectors > 0;
        }
        offsetTable.rewind();
        
        // Skip any remaining header padding
        int remaining = LRFConstants.HEADER_SIZE - buffer.position();
        if (remaining > 0) {
            buffer.position(buffer.position() + remaining);
        }
        
        LRFHeader header = new LRFHeader(version, chunkCount, compressionType, offsetTable, chunkExists);
        
        // Cache the result (limit cache size)
        if (headerCache.size() < LRFConstants.CACHE_SIZE) {
            headerCache.put(bufferHash, header);
        }
        
        return header;
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
        
        synchronized (offsetTable) {
            offsetTable.position(index * 4);
            int entry = offsetTable.getInt();
            int offsetSectors = (entry >>> 8) & 0xFFFFFF;
            return offsetSectors * 256;
        }
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
        
        synchronized (offsetTable) {
            offsetTable.position(index * 4);
            int entry = offsetTable.getInt();
            int sizeSectors = entry & 0xFF;
            return sizeSectors * 4096;
        }
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
        
        synchronized (offsetTable) {
            offsetTable.position(index * 4);
            int offsetSectors = offset / 256;
            int sizeSectors = (size + 4095) / 4096; // Round up to 4KB sectors
            int entry = (offsetSectors << 8) | (sizeSectors & 0xFF);
            offsetTable.putInt(entry);
        }
        
        chunkExists[index] = size > 0;
    }
    
    /**
     * Get offsets array for compatibility.
     */
    public int[] getOffsets() {
        int[] offsets = new int[LRFConstants.CHUNKS_PER_REGION];
        synchronized (offsetTable) {
            offsetTable.rewind();
            for (int i = 0; i < LRFConstants.CHUNKS_PER_REGION; i++) {
                int entry = offsetTable.getInt();
                int offsetSectors = (entry >>> 8) & 0xFFFFFF;
                offsets[i] = offsetSectors * 256;
            }
            offsetTable.rewind();
        }
        return offsets;
    }
    
    /**
     * Get sizes array for compatibility.
     */
    public int[] getSizes() {
        int[] sizes = new int[LRFConstants.CHUNKS_PER_REGION];
        synchronized (offsetTable) {
            offsetTable.rewind();
            for (int i = 0; i < LRFConstants.CHUNKS_PER_REGION; i++) {
                int entry = offsetTable.getInt();
                int sizeSectors = entry & 0xFF;
                sizes[i] = sizeSectors * 4096;
            }
            offsetTable.rewind();
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
