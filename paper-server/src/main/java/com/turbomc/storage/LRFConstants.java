package com.turbomc.storage;

/**
 * Constants and specifications for the Linear Region Format (LRF).
 * 
 * LRF is an optimized region file format for Minecraft world storage that provides:
 * - Sequential chunk storage (no fragmentation)
 * - Fast LZ4 compression
 * - Efficient header structure
 * - Better I/O performance than Anvil (.mca)
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public final class LRFConstants {
    
    // Format identification
    public static final byte[] MAGIC_BYTES = "TURBO_LRF".getBytes();
    public static final int MAGIC_LENGTH = MAGIC_BYTES.length;
    
    // Version
    public static final int FORMAT_VERSION = 1;
    
    // Header structure (256 bytes total)
    public static final int HEADER_SIZE = 256;
    public static final int VERSION_OFFSET = MAGIC_LENGTH;        // After magic bytes
    public static final int VERSION_SIZE = 4;
    public static final int CHUNK_COUNT_OFFSET = VERSION_OFFSET + VERSION_SIZE;
    public static final int CHUNK_COUNT_SIZE = 4;
    public static final int COMPRESSION_TYPE_OFFSET = CHUNK_COUNT_OFFSET + CHUNK_COUNT_SIZE;
    public static final int COMPRESSION_TYPE_SIZE = 4;
    public static final int OFFSETS_TABLE_OFFSET = COMPRESSION_TYPE_OFFSET + COMPRESSION_TYPE_SIZE;
    public static final int OFFSETS_TABLE_SIZE = HEADER_SIZE - OFFSETS_TABLE_OFFSET;
    
    // Region dimensions (32x32 chunks like vanilla)
    public static final int REGION_SIZE = 32;
    public static final int CHUNKS_PER_REGION = REGION_SIZE * REGION_SIZE; // 1024
    
    // Chunk metadata
    public static final int MAX_CHUNK_SIZE = 1024 * 1024; // 1MB max per chunk
    public static final int CHUNK_HEADER_SIZE = 8; // 4 bytes offset + 4 bytes size
    
    // Compression algorithms
    public static final int COMPRESSION_NONE = 0;
    public static final int COMPRESSION_ZLIB = 1;
    public static final int COMPRESSION_LZ4 = 2;
    public static final int COMPRESSION_ZSTD = 3;
    
    // File extensions
    public static final String LRF_EXTENSION = ".lrf";
    public static final String MCA_EXTENSION = ".mca";
    
    // Chunk coordinates
    public static final int CHUNK_X_MASK = 0x1F; // 31 in binary = 0001 1111
    public static final int CHUNK_Z_MASK = 0x1F;
    
    private LRFConstants() {
        // Prevent instantiation
        throw new AssertionError("LRFConstants should not be instantiated");
    }
    
    /**
     * Get chunk index in region from chunk coordinates.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Index in region (0-1023)
     */
    public static int getChunkIndex(int chunkX, int chunkZ) {
        int localX = chunkX & CHUNK_X_MASK;
        int localZ = chunkZ & CHUNK_Z_MASK;
        return localX + localZ * REGION_SIZE;
    }
    
    /**
     * Get region coordinates from chunk coordinates.
     * 
     * @param chunkCoord Chunk coordinate (X or Z)
     * @return Region coordinate
     */
    public static int getRegionCoord(int chunkCoord) {
        return chunkCoord >> 5; // Divide by 32
    }
    
    /**
     * Get compression algorithm name.
     * 
     * @param compressionType Compression type constant
     * @return Human-readable name
     */
    public static String getCompressionName(int compressionType) {
        switch (compressionType) {
            case COMPRESSION_NONE:
                return "None";
            case COMPRESSION_ZLIB:
                return "Zlib";
            case COMPRESSION_LZ4:
                return "LZ4";
            case COMPRESSION_ZSTD:
                return "ZSTD";
            default:
                return "Unknown (" + compressionType + ")";
        }
    }
}
