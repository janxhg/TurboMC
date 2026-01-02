package com.turbomc.storage.optimization;

import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;

/**
 * Storage Hooks (Disabled)
 */
public class TurboStorageHooks {

    public static void hookWrite(RegionFileStorage storage, ChunkPos pos, CompoundTag nbt) {
        // Disabled
    }

    public static void hookRead(RegionFileStorage storage, ChunkPos pos) {
        // Disabled
    }

    public static boolean areHooksInstalled() { return false; }

    public static int getActiveWrapperCount() { return 0; }

    public static void cleanupAll() {}

    public static void installHooks() {}

    public static com.turbomc.storage.optimization.TurboStorageManager.StorageManagerStats getGlobalStats() {
        return new com.turbomc.storage.optimization.TurboStorageManager.StorageManagerStats();
    }
}
