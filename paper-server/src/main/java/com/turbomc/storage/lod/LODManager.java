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
    
    // Distance thresholds (in chunks) - Loaded from config
    private int lod1Threshold;
    private int lod2Threshold;
    private int lod3Threshold;
    private boolean ultraEnabled;
    private int ultraRadius;

    private LODManager() {
        this.config = TurboConfig.getInstance();
        updateThresholds();
    }

    public static LODManager getInstance() {
        return INSTANCE;
    }

    public void updateThresholds() {
        this.lod1Threshold = config.getLOD1Distance();
        this.lod2Threshold = config.getLOD2Distance();
        this.lod3Threshold = config.getLOD3Distance();
        this.ultraEnabled = config.isUltraPrechunkingEnabled();
        this.ultraRadius = config.getUltraPrechunkingRadius();
    }

    public boolean isUltraEnabled() {
        return ultraEnabled;
    }

    public int getUltraRadius() {
        return ultraRadius;
    }

    public LODLevel getRequiredLevel(net.minecraft.server.level.ServerLevel world, int chunkX, int chunkZ) {
        if (world.players().isEmpty()) return LODLevel.FULL;
        
        int minDist = Integer.MAX_VALUE;
        for (net.minecraft.server.level.ServerPlayer player : world.players()) {
            int dx = Math.abs(player.chunkPosition().x - chunkX);
            int dz = Math.abs(player.chunkPosition().z - chunkZ);
            minDist = Math.min(minDist, Math.max(dx, dz));
        }
        
        if (minDist >= lod3Threshold) {
             // If ultra pre-chunking is enabled, we allow LOD 3 up to ultraRadius
             if (ultraEnabled && minDist <= ultraRadius) return LODLevel.LOD_3;
             // LOD 4 is for EVERYTHING beyond ultraRadius or if LOD 3 is exhausted
             return LODLevel.LOD_4;
        }
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

        if (dist >= lod3Threshold) {
            if (ultraEnabled && dist <= ultraRadius) return LODLevel.LOD_3;
            return LODLevel.LOD_4;
        }
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
        FULL,  // Close range (Full loading)
        LOD_1, // Mid range (Entities sleep)
        LOD_2, // Far range (Virtualization - terrain only)
        LOD_3, // Ultra-Far range (Predictive Marker - almost empty)
        LOD_4  // World range (Global Index - skeleton only)
    }

    /**
     * Extracts LOD 4 data from a full chunk NBT.
     */
    public void extractLOD4(String worldName, int x, int z, net.minecraft.nbt.CompoundTag nbt) {
        if (!nbt.contains("Heightmaps")) return;
        
        // Handle Optional return types if present in this Paper version
        Object heightmapsObj = nbt.getCompound("Heightmaps");
        net.minecraft.nbt.CompoundTag heightmaps;
        if (heightmapsObj instanceof java.util.Optional) {
            heightmaps = ((java.util.Optional<net.minecraft.nbt.CompoundTag>) heightmapsObj).orElse(null);
        } else {
            heightmaps = (net.minecraft.nbt.CompoundTag) heightmapsObj;
        }
        
        if (heightmaps == null || !heightmaps.contains("WORLD_SURFACE")) return;
        
        Object surfaceObj = heightmaps.getLongArray("WORLD_SURFACE");
        long[] surface;
        if (surfaceObj instanceof java.util.Optional) {
            surface = ((java.util.Optional<long[]>) surfaceObj).orElse(null);
        } else {
            surface = (long[]) surfaceObj;
        }
        
        if (surface == null || surface.length == 0) return;
        
        // Extract a sample height (from the middle of the chunk)
        int sampleHeight = (int) (surface[1] & 0x1FF); // Very rough sample
        
        GlobalIndexManager.getInstance().updateChunkInfo(worldName, x, z, 
            GlobalIndexManager.pack(true, sampleHeight, 1)); // Biome 1 for now
    }

    /**
     * Helper for hook-based extraction from SerializableChunkData.
     */
    public void extractLOD(net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.chunk.ChunkAccess chunk) {
        // TurboMC Optimization: Offload to background thread to avoid blocking chunk save/generation
        java.util.concurrent.ExecutorService executor = com.turbomc.storage.optimization.TurboStorageManager.getInstance().getPrefetchExecutor();
        if (executor == null) return; // Should not happen if initialized

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                // Map Vanilla dimension keys to directory names used by GlobalIndexManager
                String worldName = level.dimension().location().getPath();
                if (worldName.contains("overworld")) worldName = "world";
                else if (worldName.contains("nether")) worldName = "DIM-1";
                else if (worldName.contains("the_end")) worldName = "DIM1";
                
                int x = chunk.getPos().x;
                int z = chunk.getPos().z;
                
                // CRITICAL: processChunk generation typically blocks if we force heightmap calculation
                // so we only proceed if we can get it cheaply or if it's already generated.
                // For now, we try-catch to be safe and avoid crashing the background thread.
                
                // Check if HEIGHTMAPS are present in the chunk to avoid costly recalculation
                // We use WORLD_SURFACE as the visual representant
                net.minecraft.world.level.levelgen.Heightmap.Types type = net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE;
                
                // Note: getHeigthmaps() returns the map of existing heightmaps. 
                // We verify if our desired type exists before calling methods that might trigger calculation.
                // However, chunk.getOrCreateHeightmapUnprimed() usually just returns the wrapper.
                // The expensive part is 'getFirstAvailable' if it has to iterate blocks.
                // But generally for a chunk being saved, it should be populated.
                
                net.minecraft.world.level.levelgen.Heightmap hm = chunk.getOrCreateHeightmapUnprimed(type);
                if (hm != null) {
                    // This effectively reads the heightmap. If it's valid, fine.
                    int sampleHeight = hm.getFirstAvailable(8, 8);
                    
                    GlobalIndexManager.getInstance().updateChunkInfo(worldName, x, z, 
                        GlobalIndexManager.pack(true, sampleHeight, 1));
                }
            } catch (Exception e) {
                // Squelch errors in background task to not spam logs
            }
        }, executor);
    }

    /**
     * Intercepts prefetch requests to decide if they should be LOD or Full.
     */
    public boolean shouldVirtualizedPrefetch(int playerX, int playerZ, int targetX, int targetZ) {
        LODLevel level = getRequiredLevel(playerX, playerZ, targetX, targetZ);
        return level == LODLevel.LOD_2 || level == LODLevel.LOD_3 || level == LODLevel.LOD_4;
    }
}
