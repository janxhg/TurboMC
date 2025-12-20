package com.turbomc.compression;

import com.turbomc.config.TurboConfig;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Central compression service for TurboMC.
 * Handles compression/decompression with automatic format detection and fallback.
 * Thread-safe singleton.
 */
public class TurboCompressionService {
    private static TurboCompressionService instance;
    
    private final Compressor primaryCompressor;
    private final Compressor fallbackCompressor;
    private final boolean fallbackEnabled;
    
    // Statistics
    private final AtomicLong compressedBytes = new AtomicLong(0);
    private final AtomicLong decompressedBytes = new AtomicLong(0);
    private final AtomicLong compressionCount = new AtomicLong(0);
    private final AtomicLong decompressionCount = new AtomicLong(0);
    private final AtomicLong fallbackCount = new AtomicLong(0);
    
    private TurboCompressionService(TurboConfig config) {
        String algorithm = config.getCompressionAlgorithm().toLowerCase();
        int level = config.getCompressionLevel();
        this.fallbackEnabled = config.isFallbackEnabled();
        
        // Initialize primary compressor based on config
        if ("zstd".equals(algorithm)) {
            this.primaryCompressor = new ZstdCompressor(level);
            this.fallbackCompressor = new ZlibCompressor(level); // Safer fallback than LZ4 for size
        } else if ("lz4".equals(algorithm)) {
            this.primaryCompressor = new LZ4CompressorImpl(level);
            this.fallbackCompressor = new ZlibCompressor(level);
        } else {
            this.primaryCompressor = new ZlibCompressor(level);
            this.fallbackCompressor = new ZstdCompressor(Math.min(3, level)); // Try Zstd as fallback if available
        }
        
        System.out.println("[TurboMC] Compression initialized: " + primaryCompressor.getName() + 
                         " (level " + level + "), fallback " + (fallbackEnabled ? "enabled" : "disabled"));
    }
    
    public static void initialize(TurboConfig config) {
        if (instance == null) {
            instance = new TurboCompressionService(config);
        }
    }
    
    public static TurboCompressionService getInstance() {
        if (instance == null) {
            throw new IllegalStateException("TurboCompressionService not initialized!");
        }
        return instance;
    }
    
    /**
     * Compress data using the configured primary algorithm.
     *
     * @param data Raw data to compress
     * @return Compressed data with format header
     * @throws CompressionException if compression fails and fallback is disabled
     */
    public byte[] compress(byte[] data) throws CompressionException {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        
        try {
            byte[] compressed = primaryCompressor.compress(data);
            compressionCount.incrementAndGet();
            compressedBytes.addAndGet(compressed.length);
            return compressed;
        } catch (CompressionException e) {
            System.err.println("[TurboMC] Primary compression failed: " + e.getMessage());
            
            // Try fallback compression if enabled
            if (fallbackEnabled) {
                try {
                    System.out.println("[TurboMC] Trying fallback compression...");
                    byte[] fallbackCompressed = fallbackCompressor.compress(data);
                    compressionCount.incrementAndGet();
                    compressedBytes.addAndGet(fallbackCompressed.length);
                    fallbackCount.incrementAndGet();
                    return fallbackCompressed;
                } catch (CompressionException fallbackEx) {
                    System.err.println("[TurboMC] Fallback compression also failed: " + fallbackEx.getMessage());
                    throw new CompressionException("Both primary and fallback compression failed", e);
                }
            }
            
            // If no fallback or fallback failed, throw exception
            throw new CompressionException("Compression failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Decompress data with automatic format detection.
     * Falls back to alternative algorithm if primary fails and fallback is enabled.
     *
     * @param compressed Compressed data
     * @return Decompressed data
     */
    public byte[] decompress(byte[] compressed) throws java.io.IOException {
        if (compressed == null || compressed.length == 0) {
            return new byte[0];
        }
        
        try {
            // Auto-detect format based on magic byte
            byte magicByte = compressed[0];
            Compressor compressor = detectCompressor(magicByte);
            
            byte[] decompressed = compressor.decompress(compressed);
            decompressionCount.incrementAndGet();
            decompressedBytes.addAndGet(decompressed.length);
            return decompressed;
            
        } catch (CompressionException e) {
            // Try fallback if enabled
            if (fallbackEnabled) {
                try {
                    System.out.println("[TurboMC] Primary decompression failed, trying fallback...");
                    byte[] decompressed = fallbackCompressor.decompress(compressed);
                    fallbackCount.incrementAndGet();
                    decompressionCount.incrementAndGet();
                    decompressedBytes.addAndGet(decompressed.length);
                    return decompressed;
                } catch (CompressionException fallbackEx) {
                    System.err.println("[TurboMC] Fallback decompression also failed: " + fallbackEx.getMessage());
                }
            }
            
            // Critical Change: Throw Exception instead of returning garbage
            throw new java.io.IOException("Failed to decompress data (Magic: " + 
                String.format("0x%02X", compressed[0]) + ")", e);
        }
    }
    
    /**
     * Detect compressor from magic byte.
     *
     * @param magicByte First byte of compressed data
     * @return Appropriate compressor
     */
    private Compressor detectCompressor(byte magicByte) {
        if (magicByte == primaryCompressor.getMagicByte()) {
            return primaryCompressor;
        } else if (magicByte == fallbackCompressor.getMagicByte()) {
            return fallbackCompressor;
        } else {
            // Check specific known magic bytes for safety if primary/fallback changes
            // Zstd = 0x54 ('T'), LZ4 = 0x4C ('L'), Zlib = 0x78 ('x')
            if (magicByte == 0x54) return new ZstdCompressor(3);
            if (magicByte == 0x4C) return new LZ4CompressorImpl(3); 
            if (magicByte == 0x78) return new ZlibCompressor(3);
            
            // Unknown format, try primary compressor as last resort
            System.out.println("[TurboMC] Unknown compression format (magic byte: 0x" + 
                             Integer.toHexString(magicByte & 0xFF) + "), using primary");
            return primaryCompressor;
        }
    }
    
    /**
     * Get compression statistics.
     *
     * @return Statistics object
     */
    public CompressionStats getStats() {
        return new CompressionStats(
            compressionCount.get(),
            decompressionCount.get(),
            compressedBytes.get(),
            decompressedBytes.get(),
            fallbackCount.get(),
            primaryCompressor.getName()
        );
    }
    
    /**
     * Get the current primary compressor.
     *
     * @return Primary compressor
     */
    public Compressor getPrimaryCompressor() {
        return primaryCompressor;
    }
    
    /**
     * Reset statistics counters.
     */
    public void resetStats() {
        compressionCount.set(0);
        decompressionCount.set(0);
        compressedBytes.set(0);
        decompressedBytes.set(0);
        fallbackCount.set(0);
    }
}
