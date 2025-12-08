package com.turbomc.compression;

/**
 * Interface for compression algorithms used in TurboMC.
 * Implementations should be thread-safe.
 */
public interface Compressor {
    
    /**
     * Compress raw data.
     *
     * @param data Raw bytes to compress
     * @return Compressed bytes
     * @throws CompressionException if compression fails
     */
    byte[] compress(byte[] data) throws CompressionException;
    
    /**
     * Decompress compressed data.
     *
     * @param compressed Compressed bytes
     * @return Decompressed raw bytes
     * @throws CompressionException if decompression fails
     */
    byte[] decompress(byte[] compressed) throws CompressionException;
    
    /**
     * Get the name of this compression algorithm.
     *
     * @return Algorithm name (e.g., "LZ4", "Zlib")
     */
    String getName();
    
    /**
     * Get the compression level used by this compressor.
     *
     * @return Compression level (1-17 for LZ4, 1-9 for Zlib)
     */
    int getCompressionLevel();
    
    /**
     * Get the magic byte identifier for this compression format.
     * Used for auto-detection of compression type.
     *
     * @return Magic byte (0x01 for Zlib, 0x02 for LZ4)
     */
    byte getMagicByte();
}
