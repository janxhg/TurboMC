package com.turbomc.storage.integrity;

import net.minecraft.world.level.ChunkPos;

/**
 * Utility class for input validation and bounds checking in TurboMC storage operations.
 * Provides centralized validation logic to prevent invalid operations and improve robustness.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public final class ValidationUtils {
    
    private ValidationUtils() {
        // Utility class
    }
    
    /**
     * Validate chunk coordinates.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @throws IllegalArgumentException if coordinates are invalid
     */
    public static void validateChunkCoordinates(int chunkX, int chunkZ) {
        // Minecraft chunk coordinates can be negative, but should be within reasonable bounds
        if (Math.abs(chunkX) > 30_000_000 || Math.abs(chunkZ) > 30_000_000) {
            throw new IllegalArgumentException(
                String.format("Chunk coordinates out of bounds: (%d, %d). Valid range: ±30,000,000", chunkX, chunkZ));
        }
    }
    
    /**
     * Validate chunk coordinates using ChunkPos object.
     * 
     * @param pos Chunk position
     * @throws IllegalArgumentException if coordinates are invalid
     */
    public static void validateChunkCoordinates(ChunkPos pos) {
        if (pos == null) {
            throw new IllegalArgumentException("ChunkPos cannot be null");
        }
        validateChunkCoordinates(pos.x, pos.z);
    }
    
    /**
     * Validate region coordinates.
     * 
     * @param regionX Region X coordinate
     * @param regionZ Region Z coordinate
     * @throws IllegalArgumentException if coordinates are invalid
     */
    public static void validateRegionCoordinates(int regionX, int regionZ) {
        // Region coordinates should be within reasonable bounds
        if (Math.abs(regionX) > 1_000_000 || Math.abs(regionZ) > 1_000_000) {
            throw new IllegalArgumentException(
                String.format("Region coordinates out of bounds: (%d, %d). Valid range: ±1,000,000", regionX, regionZ));
        }
    }
    
    /**
     * Validate batch size parameter.
     * 
     * @param batchSize Batch size to validate
     * @param maxBatchSize Maximum allowed batch size
     * @throws IllegalArgumentException if batch size is invalid
     */
    public static void validateBatchSize(int batchSize, int maxBatchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be positive: " + batchSize);
        }
        if (batchSize > maxBatchSize) {
            throw new IllegalArgumentException(
                String.format("Batch size too large: %d. Maximum allowed: %d", batchSize, maxBatchSize));
        }
    }
    
    /**
     * Validate thread count parameter.
     * 
     * @param threadCount Thread count to validate
     * @param maxThreads Maximum allowed threads
     * @throws IllegalArgumentException if thread count is invalid
     */
    public static void validateThreadCount(int threadCount, int maxThreads) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("Thread count must be positive: " + threadCount);
        }
        if (threadCount > maxThreads) {
            throw new IllegalArgumentException(
                String.format("Thread count too large: %d. Maximum allowed: %d", threadCount, maxThreads));
        }
    }
    
    /**
     * Validate timeout parameter.
     * 
     * @param timeoutSeconds Timeout in seconds
     * @throws IllegalArgumentException if timeout is invalid
     */
    public static void validateTimeout(int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Timeout must be positive: " + timeoutSeconds);
        }
        if (timeoutSeconds > 300) { // 5 minutes max
            throw new IllegalArgumentException(
                String.format("Timeout too large: %d seconds. Maximum allowed: 300 seconds", timeoutSeconds));
        }
    }
    
    /**
     * Validate memory size parameter.
     * 
     * @param memoryMB Memory size in MB
     * @param maxMemoryMB Maximum allowed memory in MB
     * @throws IllegalArgumentException if memory size is invalid
     */
    public static void validateMemorySize(long memoryMB, long maxMemoryMB) {
        if (memoryMB <= 0) {
            throw new IllegalArgumentException("Memory size must be positive: " + memoryMB);
        }
        if (memoryMB > maxMemoryMB) {
            throw new IllegalArgumentException(
                String.format("Memory size too large: %d MB. Maximum allowed: %d MB", memoryMB, maxMemoryMB));
        }
    }
    
    /**
     * Validate cache size parameter.
     * 
     * @param cacheSize Cache size
     * @param maxCacheSize Maximum allowed cache size
     * @throws IllegalArgumentException if cache size is invalid
     */
    public static void validateCacheSize(int cacheSize, int maxCacheSize) {
        if (cacheSize < 0) {
            throw new IllegalArgumentException("Cache size cannot be negative: " + cacheSize);
        }
        if (cacheSize > maxCacheSize) {
            throw new IllegalArgumentException(
                String.format("Cache size too large: %d. Maximum allowed: %d", cacheSize, maxCacheSize));
        }
    }
    
    /**
     * Validate file path parameter.
     * 
     * @param path File path to validate
     * @param mustExist Whether the path must exist
     * @throws IllegalArgumentException if path is invalid
     */
    public static void validatePath(java.nio.file.Path path, boolean mustExist) {
        if (path == null) {
            throw new IllegalArgumentException("Path cannot be null");
        }
        
        if (mustExist && !java.nio.file.Files.exists(path)) {
            throw new IllegalArgumentException("Path does not exist: " + path);
        }
        
        // Check for invalid characters in path
        String pathString = path.toString();
        if (pathString.contains("..") && path.normalize().startsWith(path.normalize().resolve(".."))) {
            throw new IllegalArgumentException("Path contains directory traversal: " + path);
        }
    }
    
    /**
     * Validate string parameter for null/empty checks.
     * 
     * @param value String value to validate
     * @param paramName Parameter name for error messages
     * @throws IllegalArgumentException if string is null or empty
     */
    public static void validateNotNullOrEmpty(String value, String paramName) {
        if (value == null) {
            throw new IllegalArgumentException(paramName + " cannot be null");
        }
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(paramName + " cannot be empty");
        }
    }
    
    /**
     * Validate object parameter for null checks.
     * 
     * @param value Object value to validate
     * @param paramName Parameter name for error messages
     * @throws IllegalArgumentException if object is null
     */
    public static void validateNotNull(Object value, String paramName) {
        if (value == null) {
            throw new IllegalArgumentException(paramName + " cannot be null");
        }
    }
    
    /**
     * Validate array/collection size.
     * 
     * @param size Size to validate
     * @param maxSize Maximum allowed size
     * @param name Name of the collection for error messages
     * @throws IllegalArgumentException if size is invalid
     */
    public static void validateCollectionSize(int size, int maxSize, String name) {
        if (size < 0) {
            throw new IllegalArgumentException(name + " size cannot be negative: " + size);
        }
        if (size > maxSize) {
            throw new IllegalArgumentException(
                String.format("%s size too large: %d. Maximum allowed: %d", name, size, maxSize));
        }
    }
    
    /**
     * Validate percentage value (0.0 to 1.0).
     * 
     * @param percentage Percentage value to validate
     * @param paramName Parameter name for error messages
     * @throws IllegalArgumentException if percentage is invalid
     */
    public static void validatePercentage(double percentage, String paramName) {
        if (percentage < 0.0 || percentage > 1.0) {
            throw new IllegalArgumentException(
                String.format("%s must be between 0.0 and 1.0: %f", paramName, percentage));
        }
    }
    
    /**
     * Validate retry count.
     * 
     * @param retryCount Retry count to validate
     * @param maxRetries Maximum allowed retries
     * @throws IllegalArgumentException if retry count is invalid
     */
    public static void validateRetryCount(int retryCount, int maxRetries) {
        if (retryCount < 0) {
            throw new IllegalArgumentException("Retry count cannot be negative: " + retryCount);
        }
        if (retryCount > maxRetries) {
            throw new IllegalArgumentException(
                String.format("Retry count too large: %d. Maximum allowed: %d", retryCount, maxRetries));
        }
    }
}
