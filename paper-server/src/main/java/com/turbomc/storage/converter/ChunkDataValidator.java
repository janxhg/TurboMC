package com.turbomc.storage.converter;

import com.turbomc.storage.lrf.LRFChunkEntry;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates chunk data integrity during conversion processes.
 * Detects corruption, invalid coordinates, and malformed data.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class ChunkDataValidator {
    
    private static final int MAX_CHUNK_SIZE = 16 * 16 * 256; // Maximum possible chunk data size
    private static final int MIN_CHUNK_SIZE = 100; // Minimum reasonable chunk size
    private final Set<Integer> seenChunkCoords;
    private int validationErrors;
    
    public ChunkDataValidator() {
        this.seenChunkCoords = new HashSet<>();
        this.validationErrors = 0;
    }
    
    /**
     * Validate a chunk entry.
     * 
     * @param chunk Chunk entry to validate
     * @return True if chunk is valid
     */
    public boolean validateChunk(LRFChunkEntry chunk) {
        if (chunk == null) {
            reportError("Null chunk detected");
            return false;
        }
        
        // Validate coordinates
        if (!isValidChunkCoordinate(chunk.getChunkX())) {
            reportError("Invalid chunk X coordinate: " + chunk.getChunkX());
            return false;
        }
        
        if (!isValidChunkCoordinate(chunk.getChunkZ())) {
            reportError("Invalid chunk Z coordinate: " + chunk.getChunkZ());
            return false;
        }
        
        // Check for duplicate chunks (shouldn't happen but good to catch)
        int chunkKey = chunk.getChunkX() * 1000 + chunk.getChunkZ();
        if (seenChunkCoords.contains(chunkKey)) {
            reportError("Duplicate chunk coordinates: " + chunk.getChunkX() + "," + chunk.getChunkZ());
            return false;
        }
        seenChunkCoords.add(chunkKey);
        
        // Validate data
        byte[] data = chunk.getData();
        if (data == null) {
            reportError("Null data for chunk " + chunk.getChunkX() + "," + chunk.getChunkZ());
            return false;
        }
        
        if (data.length == 0) {
            reportError("Empty data for chunk " + chunk.getChunkX() + "," + chunk.getChunkZ());
            return false;
        }
        
        if (data.length > MAX_CHUNK_SIZE) {
            reportError("Chunk data too large: " + data.length + " bytes for " + 
                       chunk.getChunkX() + "," + chunk.getChunkZ());
            return false;
        }
        
        if (data.length < MIN_CHUNK_SIZE) {
            reportWarning("Suspiciously small chunk data: " + data.length + " bytes for " + 
                         chunk.getChunkX() + "," + chunk.getChunkZ());
        }
        
        // Validate NBT structure (basic check)
        if (!isValidNBTData(data)) {
            reportError("Invalid NBT structure in chunk " + chunk.getChunkX() + "," + chunk.getChunkZ());
            return false;
        }
        
        return true;
    }
    
    /**
     * Check if chunk coordinate is within valid range.
     * 
     * @param coord Chunk coordinate
     * @return True if coordinate is valid
     */
    private boolean isValidChunkCoordinate(int coord) {
        // Minecraft chunks are typically within reasonable bounds
        // Allow for reasonable world sizes, but catch extreme values
        return coord >= -30000000 && coord <= 30000000;
    }
    
    /**
     * Basic validation of NBT data structure.
     * This is a simplified check - a full NBT parser would be more thorough.
     * 
     * @param data NBT data to validate
     * @return True if data appears to be valid NBT
     */
    private boolean isValidNBTData(byte[] data) {
        if (data.length < 3) {
            return false;
        }
        
        // Check for common NBT tag types
        // Compound tag (0x0A), List tag (0x09), Byte tag (0x01), etc.
        Set<Byte> validStartTags = Set.of(
            (byte) 0x0A, // Compound
            (byte) 0x09, // List
            (byte) 0x01, // Byte
            (byte) 0x02, // Short
            (byte) 0x03, // Int
            (byte) 0x04, // Long
            (byte) 0x05, // Float
            (byte) 0x06, // Double
            (byte) 0x07, // Byte array
            (byte) 0x08, // String
            (byte) 0x0B, // Int array
            (byte) 0x0C, // Long array
            (byte) 0x00  // End
        );
        
        return validStartTags.contains(data[0]);
    }
    
    /**
     * Validate compression integrity.
     * 
     * @param originalData Original uncompressed data
     * @param compressedData Compressed data
     * @param algorithm Compression algorithm used
     * @return True if compression appears valid
     */
    public boolean validateCompression(byte[] originalData, byte[] compressedData, String algorithm) {
        if (originalData == null || compressedData == null) {
            reportError("Null data in compression validation");
            return false;
        }
        
        if (compressedData.length > originalData.length * 2) {
            // Compression should not result in data larger than 2x original
            // (accounting for compression headers)
            reportWarning("Suspicious compression ratio for " + algorithm + ": " + 
                         compressedData.length + " bytes from " + originalData.length + " bytes");
        }
        
        if (compressedData.length < originalData.length / 10) {
            // Extremely aggressive compression might indicate corruption
            reportWarning("Extremely high compression ratio for " + algorithm + ": " + 
                         compressedData.length + " bytes from " + originalData.length + " bytes");
        }
        
        return true;
    }
    
    /**
     * Reset validation state for new conversion batch.
     */
    public void reset() {
        seenChunkCoords.clear();
        validationErrors = 0;
    }
    
    /**
     * Report validation error.
     */
    private void reportError(String message) {
        validationErrors++;
        System.err.println("[TurboMC] VALIDATION ERROR: " + message);
    }
    
    /**
     * Report validation warning.
     */
    private void reportWarning(String message) {
        System.out.println("[TurboMC] VALIDATION WARNING: " + message);
    }
    
    /**
     * Get validation statistics.
     */
    public ValidationStats getStats() {
        return new ValidationStats(
            seenChunkCoords.size(),
            validationErrors
        );
    }
    
    /**
     * Check if validation passed.
     */
    public boolean isValid() {
        return validationErrors == 0;
    }
    
    /**
     * Validation statistics holder.
     */
    public static class ValidationStats {
        public final int chunksValidated;
        public final int validationErrors;
        
        public ValidationStats(int chunksValidated, int validationErrors) {
            this.chunksValidated = chunksValidated;
            this.validationErrors = validationErrors;
        }
        
        @Override
        public String toString() {
            return String.format("ValidationStats{chunks=%d, errors=%d, valid=%s}",
                    chunksValidated, validationErrors, validationErrors == 0 ? "YES" : "NO");
        }
    }
}
