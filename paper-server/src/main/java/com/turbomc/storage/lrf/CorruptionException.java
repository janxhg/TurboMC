package com.turbomc.storage.lrf;

import java.io.IOException;

/**
 * Exception thrown when LRF chunk data is corrupted and cannot be read normally.
 * This exception provides detailed information about the corruption for repair attempts.
 * 
 * @author TurboMC
 */
public class CorruptionException extends IOException {
    private final int chunkX;
    private final int chunkZ;
    private final String corruptionType;
    
    public CorruptionException(String message, int chunkX, int chunkZ) {
        super(message);
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.corruptionType = "UNKNOWN";
    }
    
    public CorruptionException(String message, int chunkX, int chunkZ, String corruptionType) {
        super(message);
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.corruptionType = corruptionType;
    }
    
    public CorruptionException(String message, Throwable cause, int chunkX, int chunkZ) {
        super(message, cause);
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.corruptionType = "UNKNOWN";
    }
    
    public int getChunkX() {
        return chunkX;
    }
    
    public int getChunkZ() {
        return chunkZ;
    }
    
    public String getCorruptionType() {
        return corruptionType;
    }
    
    @Override
    public String toString() {
        return String.format("CorruptionException[chunk=%d,%d,type=%s]: %s", 
                           chunkX, chunkZ, corruptionType, getMessage());
    }
}