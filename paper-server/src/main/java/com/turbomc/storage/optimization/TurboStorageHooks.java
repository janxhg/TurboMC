package com.turbomc.storage.optimization;

import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Centralized hooks for TurboMC storage optimizations.
 * Provides deep integration points for chunk I/O and region file management.
 */
public class TurboStorageHooks {

    public static boolean shouldUseLRF() {
        return "lrf".equalsIgnoreCase(com.turbomc.config.TurboConfig.getInstance().getStorageFormat());
    }

    public static RegionFile createRegionFile(RegionStorageInfo info, Path path, Path folder, boolean sync) throws IOException {
        // STRICT CHECK: Only use LRF adapter if the file extension is .lrf
        // This prevents creating corrupt .mca files with LRF headers
        if (path.toString().endsWith(".lrf")) {
             return new com.turbomc.storage.lrf.LRFRegionFileAdapter(info, path);
        }
        
        // Legacy/Fallback: Return standard RegionFile for .mca files
        // This ensures they are read/written using Anvil format, preventing "invalid sector" errors
        return new RegionFile(info, path, folder, sync);
    }

    public static CompoundTag hookRead(RegionFileStorage storage, ChunkPos pos) throws IOException {
        // Redundant with direct injection, but kept for compatibility if needed
        return null; 
    }

    public static boolean hookWrite(RegionFileStorage storage, RegionFile regionFile, ChunkPos pos, CompoundTag nbt) throws IOException {
        if (regionFile instanceof com.turbomc.storage.lrf.LRFRegionFileAdapter lrf) {
            // Optimized write path: Direct CompoundTag to LRF (PackedBinaryNBT)
            // No need for DataOutputStream or double-serialization
            lrf.writeOptimized(pos, nbt);
            return true;
        }
        return false;
    }

    public static void hookClose(RegionFileStorage storage) throws IOException {
        // Do not call storage.close() here as it causes infinite recursion.
    }

    public static void hookFlush(RegionFileStorage storage) throws IOException {
        // Do not call storage.flush() here as it causes infinite recursion.
        // RegionFileStorage.flush() already iterates its cache and flushes files.
        // If we need to flush TurboStorageManager's internal buffers for this storage's folder,
        // we could do it here, but TurboStorageManager handles its own consistency.
    }

    public static TurboStorageManager.StorageManagerStats getGlobalStats() {
        return TurboStorageManager.getInstance().getStats();
    }

    public static boolean areHooksInstalled() {
        return true;
    }

    public static int getActiveWrapperCount() {
        return TurboStorageManager.getInstance().getActiveRegionCount();
    }

    public static void cleanupAll() {
        try {
            TurboStorageManager.getInstance().close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void installHooks() {
        System.out.println("[TurboMC][Hook] Deep integration hooks ready.");
    }
}
