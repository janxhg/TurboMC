package com.turbomc.config;

import com.turbomc.storage.LRFConstants;

/**
 * Configuration for TurboMC compression settings.
 * Provides centralized management of compression parameters and validation.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class TurboCompressionConfig {
    
    private final String algorithm;
    private final int level;
    private final boolean enableFallback;
    private final boolean enableValidation;
    private final boolean enableRecovery;
    private final boolean enableBackups;
    private final boolean streamingMode;
    
    public TurboCompressionConfig(String algorithm, int level, boolean enableFallback, 
                                boolean enableValidation, boolean enableRecovery, 
                                boolean enableBackups, boolean streamingMode) {
        this.algorithm = algorithm;
        this.level = Math.max(1, Math.min(17, level));
        this.enableFallback = enableFallback;
        this.enableValidation = enableValidation;
        this.enableRecovery = enableRecovery;
        this.enableBackups = enableBackups;
        this.streamingMode = streamingMode;
    }
    
    /**
     * Create compression config with sensible defaults.
     */
    public static TurboCompressionConfig defaultConfig() {
        return new TurboCompressionConfig(
            "lz4",                    // Algorithm
            6,                       // Level (fast compression)
            true,                    // Enable fallback
            true,                    // Enable validation
            true,                    // Enable recovery
            true,                    // Enable backups
            false                    // Streaming mode
        );
    }
    
    /**
     * Create compression config for maximum performance.
     */
    public static TurboCompressionConfig performanceConfig() {
        return new TurboCompressionConfig(
            "lz4",                    // Algorithm
            3,                       // Level (fastest compression)
            false,                   // Disable fallback for speed
            false,                   // Disable validation for speed
            true,                    // Keep recovery
            false,                   // Disable backups for speed
            true                     // Enable streaming mode
        );
    }
    
    /**
     * Create compression config for maximum compression.
     */
    public static TurboCompressionConfig compressionConfig() {
        return new TurboCompressionConfig(
            "zstd",                   // Algorithm
            9,                       // Level (maximum compression)
            true,                    // Enable fallback
            true,                    // Enable validation
            true,                    // Enable recovery
            true,                    // Enable backups
            false                    // Disable streaming
        );
    }
    
    /**
     * Create compression config for development/testing.
     */
    public static TurboCompressionConfig developmentConfig() {
        return new TurboCompressionConfig(
            "lz4",                    // Algorithm
            1,                       // Level (fastest)
            true,                    // Enable fallback
            true,                    // Enable validation
            true,                    // Enable recovery
            true,                    // Enable backups
            false                    // Disable streaming
        );
    }
    
    /**
     * Get compression algorithm.
     */
    public String getAlgorithm() {
        return algorithm;
    }
    
    /**
     * Get compression level.
     */
    public int getLevel() {
        return level;
    }
    
    /**
     * Check if fallback is enabled.
     */
    public boolean isFallbackEnabled() {
        return enableFallback;
    }
    
    /**
     * Check if validation is enabled.
     */
    public boolean isValidationEnabled() {
        return enableValidation;
    }
    
    /**
     * Check if recovery is enabled.
     */
    public boolean isRecoveryEnabled() {
        return enableRecovery;
    }
    
    /**
     * Check if backups are enabled.
     */
    public boolean isBackupsEnabled() {
        return enableBackups;
    }
    
    /**
     * Check if streaming mode is enabled.
     */
    public boolean isStreamingMode() {
        return streamingMode;
    }
    
    /**
     * Get compression type constant for LRF.
     */
    public int getCompressionType() {
        return switch (algorithm.toLowerCase()) {
            case "lz4" -> LRFConstants.COMPRESSION_LZ4;
            case "zstd" -> LRFConstants.COMPRESSION_ZSTD;
            case "zlib", "gzip" -> LRFConstants.COMPRESSION_ZLIB;
            case "none" -> LRFConstants.COMPRESSION_NONE;
            default -> LRFConstants.COMPRESSION_LZ4; // Default fallback
        };
    }
    
    /**
     * Validate configuration.
     * 
     * @return True if configuration is valid
     */
    public boolean isValid() {
        if (algorithm == null || algorithm.trim().isEmpty()) {
            return false;
        }
        
        if (level < 1 || level > 17) {
            return false;
        }
        
        // Validate algorithm
        String validAlgorithm = algorithm.toLowerCase();
        return validAlgorithm.equals("lz4") || 
               validAlgorithm.equals("zstd") || 
               validAlgorithm.equals("zlib") || 
               validAlgorithm.equals("gzip") || 
               validAlgorithm.equals("none");
    }
    
    /**
     * Get configuration summary.
     */
    public String getSummary() {
        return String.format("CompressionConfig{algorithm='%s', level=%d, fallback=%s, validation=%s, recovery=%s, backups=%s, streaming=%s}",
                algorithm, level, enableFallback, enableValidation, enableRecovery, enableBackups, streamingMode);
    }
    
    @Override
    public String toString() {
        return getSummary();
    }
}
