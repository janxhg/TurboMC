package com.turbomc.storage.lod;

import com.turbomc.config.TurboConfig;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the lifecycle and serving of Level-of-Detail (LOD) chunks.
 * Orchestrates virtualization based on player distance.
 */
public class LODManager {
    private static final LODManager INSTANCE = new LODManager();
    
    private final Map<Long, LODChunk> lodCache = new ConcurrentHashMap<>();
    private final TurboConfig config;
    
    // Distance thresholds (in chunks)
    private int lod1Threshold = 8; // Mid (Entities sleep)
    private int lod2Threshold = 16; // Far (Virtualization)
    private int lod3Threshold = 32; // Ultra-Far (Predictive Marker)

    private LODManager() {
        this.config = TurboConfig.getInstance();
        updateThresholds();
    }

    public static LODManager getInstance() {
        return INSTANCE;
    }

    public void updateThresholds() {
        // Values from config when implemented there
        // this.lod0Threshold = config.getLOD0Threshold();
    }

    public LODLevel getRequiredLevel(net.minecraft.server.level.ServerLevel world, int chunkX, int chunkZ) {
        if (world.players().isEmpty()) return LODLevel.FULL;
        
        int minDist = Integer.MAX_VALUE;
        for (net.minecraft.server.level.ServerPlayer player : world.players()) {
            int dx = Math.abs(player.chunkPosition().x - chunkX);
            int dz = Math.abs(player.chunkPosition().z - chunkZ);
            minDist = Math.min(minDist, Math.max(dx, dz));
        }
        
        if (minDist >= lod3Threshold) return LODLevel.LOD_3;
        if (minDist >= lod2Threshold) return LODLevel.LOD_2;
        if (minDist >= lod1Threshold) return LODLevel.LOD_1;
        return LODLevel.FULL;
    }

    /**
     * Coordinate-based version for low-level callers (e.g. Prefetcher).
     */
    public LODLevel getRequiredLevel(int playerX, int playerZ, int chunkX, int chunkZ) {
        int dx = Math.abs(playerX - chunkX);
        int dz = Math.abs(playerZ - chunkZ);
        int dist = Math.max(dx, dz);

        if (dist >= lod3Threshold) return LODLevel.LOD_3;
        if (dist >= lod2Threshold) return LODLevel.LOD_2;
        if (dist >= lod1Threshold) return LODLevel.LOD_1;
        return LODLevel.FULL;
    }

    /**
     * Applies LOD 0 data to a LevelChunk.
     */
    public void applyLOD(net.minecraft.world.level.chunk.LevelChunk chunk, LODChunk lod) {
        if (lod == null) return;
        // Set heightmap and surface data if needed for rendering
        // For now, we rely on the client to handle empty chunks gracefully or populate it on main
    }

    public void cacheLOD(LODChunk chunk) {
        lodCache.put(getChunkKey(chunk.getX(), chunk.getZ()), chunk);
    }

    public LODChunk getLOD(int x, int z) {
        return lodCache.get(getChunkKey(x, z));
    }

    private long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public enum LODLevel {
        FULL,  // 0-8 chunks
        LOD_1, // 9-16 chunks (Entities sleep)
        LOD_2, // 17-32 chunks (Virtualization - terrain only)
        LOD_3  // 33-64 chunks (Predictive Marker - almost empty)
    }

    /**
     * Extracts LOD 0 data from a full chunk.
     */
    public LODChunk extractLOD(net.minecraft.world.level.chunk.ChunkAccess chunk) {
        if (!(chunk instanceof net.minecraft.world.level.chunk.LevelChunk)) return null;
        net.minecraft.world.level.chunk.LevelChunk levelChunk = (net.minecraft.world.level.chunk.LevelChunk) chunk;
        int x = chunk.getPos().x;
        int z = chunk.getPos().z;
        byte[] heightmap = new byte[256];
        short[] surfaceBlocks = new short[256];

        net.minecraft.world.level.levelgen.Heightmap hm = chunk.getOrCreateHeightmapUnprimed(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE);
        
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                int y = hm.getFirstAvailable(i, j) - 1;
                heightmap[i * 16 + j] = (byte) Math.max(-128, Math.min(127, y));
                
                // Get surface block type (simplified)
                net.minecraft.world.level.block.state.BlockState state = chunk.getBlockState(i, y, j);
                surfaceBlocks[i * 16 + j] = (short) net.minecraft.world.level.block.Block.getId(state);
            }
        }

        LODChunk lod = new LODChunk(x, z, heightmap, surfaceBlocks);
        cacheLOD(lod);
        return lod;
    }

    /**
     * Intercepts prefetch requests to decide if they should be LOD or Full.
     */
    public boolean shouldVirtualizedPrefetch(int playerX, int playerZ, int targetX, int targetZ) {
        LODLevel level = getRequiredLevel(playerX, playerZ, targetX, targetZ);
        return level == LODLevel.LOD_2 || level == LODLevel.LOD_3;
    }
}
