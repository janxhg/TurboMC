package com.turbomc.storage.lod;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;

/**
 * A Level-of-Detail (LOD) representation of a chunk.
 * Stores minimal data required for rendering or low-fidelity processing.
 */
public class LODChunk implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int x;
    private final int z;
    private final byte[] heightmap; // 16x16 surface elevations
    private final short[] surfaceBlocks; // Dominant block types at surface
    private final long timestamp;

    public LODChunk(int x, int z, byte[] heightmap, short[] surfaceBlocks) {
        this.x = x;
        this.z = z;
        this.heightmap = heightmap;
        this.surfaceBlocks = surfaceBlocks;
        this.timestamp = System.currentTimeMillis();
    }

    public int getX() { return x; }
    public int getZ() { return z; }
    public byte[] getHeightmap() { return heightmap; }
    public short[] getSurfaceBlocks() { return surfaceBlocks; }
    public long getTimestamp() { return timestamp; }

    /**
     * Estimates memory weight of this LOD chunk in bytes.
     */
    public int getWeight() {
        return (heightmap != null ? heightmap.length : 0) + 
               (surfaceBlocks != null ? surfaceBlocks.length * 2 : 0) + 24;
    }

    @Override
    public String toString() {
        return "LODChunk[" + x + "," + z + "]";
    }
}
