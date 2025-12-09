package com.turbomc.compression;

/**
 * Statistics for compression operations.
 */
public record CompressionStats(
    long compressionCount,
    long decompressionCount,
    long compressedBytes,
    long decompressedBytes,
    long fallbackCount,
    String primaryAlgorithm
) {
    
    public double getCompressionRatio() {
        if (decompressedBytes == 0) return 0.0;
        return (double) compressedBytes / decompressedBytes;
    }
    
    public double getAverageCompressedSize() {
        if (compressionCount == 0) return 0.0;
        return (double) compressedBytes / compressionCount;
    }
    
    public double getAverageDecompressedSize() {
        if (decompressionCount == 0) return 0.0;
        return (double) decompressedBytes / decompressionCount;
    }
    
    @Override
    public String toString() {
        return String.format("""
            Compression Statistics:
              Algorithm: %s
              Compressions: %,d (avg size: %.2f bytes)
              Decompressions: %,d (avg size: %.2f bytes)
              Compression Ratio: %.2f%%
              Fallback Uses: %,d
            """,
            primaryAlgorithm,
            compressionCount, getAverageCompressedSize(),
            decompressionCount, getAverageDecompressedSize(),
            getCompressionRatio() * 100,
            fallbackCount
        );
    }
}
