package com.turbomc.storage.optimization;

/**
 * Transport object for compressed chunk data.
 * Facilitates the separation of I/O (Fetch) and CPU (Inflate) stages.
 */
public class CompressedChunkPacket {
    public final int chunkX;
    public final int chunkZ;
    public final byte[] data; // Compressed payload
    public final int length;
    public final int compressionType;
    
    public CompressedChunkPacket(int chunkX, int chunkZ, byte[] data, int length, int compressionType) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.data = data;
        this.length = length;
        this.compressionType = compressionType;
    }
}
