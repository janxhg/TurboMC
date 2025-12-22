package com.turbomc.compression;

import com.turbomc.storage.lrf.LRFConstants;

/**
 * Validates compression levels to prevent corruption from overly aggressive compression.
 * Provides safe compression settings for different use cases.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class CompressionLevelValidator {
    
    // Safe compression levels for different algorithms
    private static final int MAX_SAFE_LZ4_LEVEL = 9;    // LZ4 level 9 is safe
    private static final int MAX_SAFE_ZSTD_LEVEL = 15;  // ZSTD level 15 is safe
    private static final int MAX_SAFE_ZLIB_LEVEL = 9;   // Zlib level 9 is safe
    
    // Unsafe levels that have caused corruption
    private static final int UNSAFE_ZSTD_LEVEL = 20;    // Level 20+ is dangerous
    private static final int UNSAFE_LZ4_LEVEL = 15;     // Level 15+ is dangerous
    
    /**
     * Validate and potentially adjust compression level to safe values.
     * 
     * @param algorithm Compression algorithm
     * @param requestedLevel Requested compression level
     * @param context Context for validation (e.g., "conversion", "runtime")
     * @return Safe compression level
     */
    public static int validateAndAdjustLevel(String algorithm, int requestedLevel, String context) {
        String algo = algorithm.toLowerCase();
        int safeLevel = requestedLevel;
        
        // Check if level is within valid range
        if (requestedLevel < 1) {
            System.err.println("[TurboMC][Compression] Invalid compression level " + requestedLevel + 
                             " for " + algo + ", using level 1");
            safeLevel = 1;
        }
        
        // Algorithm-specific validation
        switch (algo) {
            case "lz4":
                safeLevel = validateLZ4Level(requestedLevel, context);
                break;
            case "zstd":
                safeLevel = validateZSTDLevel(requestedLevel, context);
                break;
            case "zlib":
            case "gzip":
                safeLevel = validateZlibLevel(requestedLevel, context);
                break;
            default:
                System.err.println("[TurboMC][Compression] Unknown algorithm " + algo + 
                                 ", defaulting to LZ4 level 6");
                safeLevel = validateLZ4Level(6, context);
        }
        
        // Log if level was adjusted
        if (safeLevel != requestedLevel) {
            System.out.println("[TurboMC][Compression] Adjusted compression level from " + 
                             requestedLevel + " to " + safeLevel + " for " + algo + 
                             " (" + context + ")");
        }
        
        return safeLevel;
    }
    
    /**
     * Validate LZ4 compression level.
     */
    private static int validateLZ4Level(int level, String context) {
        if (level > MAX_SAFE_LZ4_LEVEL) {
            System.err.println("[TurboMC][Compression] LZ4 level " + level + 
                             " is too aggressive, limiting to " + MAX_SAFE_LZ4_LEVEL);
            return MAX_SAFE_LZ4_LEVEL;
        }
        
        // Context-specific adjustments
        if ("full-conversion".equals(context) || "migration".equals(context)) {
            // Be more conservative during conversion
            int conversionSafeLevel = Math.min(level, 6);
            if (conversionSafeLevel != level) {
                System.out.println("[TurboMC][Compression] Using conservative LZ4 level " + 
                                 conversionSafeLevel + " for " + context);
                return conversionSafeLevel;
            }
        }
        
        return level;
    }
    
    /**
     * Validate ZSTD compression level.
     */
    private static int validateZSTDLevel(int level, String context) {
        if (level >= UNSAFE_ZSTD_LEVEL) {
            System.err.println("[TurboMC][Compression] ZSTD level " + level + 
                             " is unsafe and can cause corruption, limiting to " + 
                             MAX_SAFE_ZSTD_LEVEL);
            return MAX_SAFE_ZSTD_LEVEL;
        }
        
        // Context-specific adjustments
        if ("full-conversion".equals(context) || "migration".equals(context)) {
            // Very conservative during conversion to avoid corruption
            int conversionSafeLevel = Math.min(level, 9);
            if (conversionSafeLevel != level) {
                System.out.println("[TurboMC][Compression] Using conservative ZSTD level " + 
                                 conversionSafeLevel + " for " + context);
                return conversionSafeLevel;
            }
        } else if ("runtime".equals(context)) {
            // Runtime compression should be fast
            int runtimeSafeLevel = Math.min(level, 3);
            if (runtimeSafeLevel != level) {
                System.out.println("[TurboMC][Compression] Using fast ZSTD level " + 
                                 runtimeSafeLevel + " for " + context);
                return runtimeSafeLevel;
            }
        }
        
        return level;
    }
    
    /**
     * Validate Zlib compression level.
     */
    private static int validateZlibLevel(int level, String context) {
        if (level > MAX_SAFE_ZLIB_LEVEL) {
            System.err.println("[TurboMC][Compression] Zlib level " + level + 
                             " is too aggressive, limiting to " + MAX_SAFE_ZLIB_LEVEL);
            return MAX_SAFE_ZLIB_LEVEL;
        }
        
        return level;
    }
    
    /**
     * Get recommended compression settings for different scenarios.
     */
    public static CompressionSettings getRecommendedSettings(String scenario) {
        switch (scenario.toLowerCase()) {
            case "safe-conversion":
                return new CompressionSettings("lz4", 6, false, "Safe for conversion, medium compression");
                
            case "fast-conversion":
                return new CompressionSettings("lz4", 3, false, "Fast conversion, lower compression");
                
            case "maximum-compression":
                return new CompressionSettings("zstd", MAX_SAFE_ZSTD_LEVEL, true, 
                                             "Maximum safe compression");
                
            case "development":
                return new CompressionSettings("lz4", 1, false, "Fastest for development");
                
            case "production":
                return new CompressionSettings("lz4", 6, true, "Balanced production settings");
                
            default:
                return new CompressionSettings("lz4", 6, true, "Default settings");
        }
    }
    
    /**
     * Check if a compression configuration is safe for full conversion.
     * 
     * @param algorithm Compression algorithm
     * @param level Compression level
     * @return True if safe for conversion
     */
    public static boolean isSafeForFullConversion(String algorithm, int level) {
        String algo = algorithm.toLowerCase();
        
        switch (algo) {
            case "lz4":
                return level <= 6;
            case "zstd":
                return level <= 9;
            case "zlib":
            case "gzip":
                return level <= 6;
            default:
                return false;
        }
    }
    
    /**
     * Get maximum safe compression level for an algorithm.
     */
    public static int getMaxSafeLevel(String algorithm) {
        switch (algorithm.toLowerCase()) {
            case "lz4": return MAX_SAFE_LZ4_LEVEL;
            case "zstd": return MAX_SAFE_ZSTD_LEVEL;
            case "zlib": return MAX_SAFE_ZLIB_LEVEL;
            default: return 6;
        }
    }
    
    /**
     * Compression settings holder.
     */
    public static class CompressionSettings {
        public final String algorithm;
        public final int level;
        public final boolean enableFallback;
        public final String description;
        
        CompressionSettings(String algorithm, int level, boolean enableFallback, String description) {
            this.algorithm = algorithm;
            this.level = level;
            this.enableFallback = enableFallback;
            this.description = description;
        }
        
        @Override
        public String toString() {
            return String.format("CompressionSettings{algorithm='%s', level=%d, fallback=%s, description='%s'}",
                    algorithm, level, enableFallback, description);
        }
    }
}
