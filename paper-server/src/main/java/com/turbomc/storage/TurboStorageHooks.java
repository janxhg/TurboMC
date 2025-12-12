package com.turbomc.storage;

import com.turbomc.config.TurboConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * Runtime hooking system for integrating TurboMC storage optimizations with Paper's chunk system.
 * This class uses reflection to inject TurboMC components into Paper's storage classes at runtime.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public final class TurboStorageHooks {
    
    private static final AtomicBoolean hooksInstalled = new AtomicBoolean(false);
    private static final ConcurrentHashMap<Object, TurboRegionFileStorage> turboStorageWrappers = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Object, TurboIOWorker> turboWorkerWrappers = new ConcurrentHashMap<>();
    
    private TurboStorageHooks() {
        // Utility class
    }
    
    /**
     * Install TurboMC storage hooks into Paper's chunk system.
     * This should be called during server startup after TurboConfig is initialized.
     */
    public static void installHooks() {
        if (hooksInstalled.compareAndSet(false, true)) {
            System.out.println("[TurboMC][Hooks] Installing storage hooks...");
            
            try {
                // Hook into RegionFileStorage creation
                hookRegionFileStorage();
                
                // Hook into IOWorker creation  
                hookIOWorker();
                
                System.out.println("[TurboMC][Hooks] Storage hooks installed successfully!");
            } catch (Exception e) {
                System.err.println("[TurboMC][Hooks] Failed to install storage hooks: " + e.getMessage());
                e.printStackTrace();
                hooksInstalled.set(false);
            }
        }
    }
    
    /**
     * Get or create a TurboRegionFileStorage wrapper for a vanilla RegionFileStorage.
     */
    public static TurboRegionFileStorage getTurboStorage(RegionFileStorage vanillaStorage) {
        return turboStorageWrappers.computeIfAbsent(vanillaStorage, storage -> {
            try {
                // Extract needed information from vanilla storage
                Path folder = extractStorageFolder(vanillaStorage);
                boolean sync = extractSyncFlag(vanillaStorage);
                RegionStorageInfo info = extractStorageInfo(vanillaStorage);
                
                if (folder != null && info != null) {
                    return new TurboRegionFileStorage(info, folder, sync);
                }
            } catch (Exception e) {
                System.err.println("[TurboMC][Hooks] Failed to create Turbo storage wrapper: " + e.getMessage());
            }
            
            return null;
        });
    }
    
    /**
     * Get or create a TurboIOWorker wrapper for a vanilla IOWorker.
     */
    public static TurboIOWorker getTurboWorker(IOWorker vanillaWorker) {
        return turboWorkerWrappers.computeIfAbsent(vanillaWorker, worker -> {
            try {
                // Extract needed information from vanilla worker
                Path folder = extractWorkerFolder(vanillaWorker);
                boolean sync = extractWorkerSyncFlag(vanillaWorker);
                RegionStorageInfo info = extractWorkerStorageInfo(vanillaWorker);
                
                if (folder != null && info != null) {
                    return new TurboIOWorker(info, folder, sync);
                }
            } catch (Exception e) {
                System.err.println("[TurboMC][Hooks] Failed to create Turbo worker wrapper: " + e.getMessage());
            }
            
            return null;
        });
    }
    
    /**
     * Enhanced chunk read method that uses TurboMC optimizations.
     */
    @Nullable
    public static net.minecraft.nbt.CompoundTag readChunkEnhanced(RegionFileStorage storage, net.minecraft.world.level.ChunkPos pos) throws java.io.IOException {
        if (!isTurboEnabled()) {
            return storage.read(pos);
        }
        
        TurboRegionFileStorage turboStorage = getTurboStorage(storage);
        if (turboStorage != null) {
            return turboStorage.read(pos);
        }
        
        return storage.read(pos);
    }
    
    /**
     * Enhanced chunk write method that uses TurboMC optimizations.
     */
    public static void writeChunkEnhanced(RegionFileStorage storage, net.minecraft.world.level.ChunkPos pos, net.minecraft.nbt.CompoundTag nbt) throws java.io.IOException {
        if (!isTurboEnabled()) {
            storage.write(pos, nbt);
            return;
        }
        
        TurboRegionFileStorage turboStorage = getTurboStorage(storage);
        if (turboStorage != null) {
            turboStorage.write(pos, nbt);
            return;
        }
        
        storage.write(pos, nbt);
    }
    
    /**
     * Check if TurboMC features are enabled.
     */
    private static boolean isTurboEnabled() {
        if (!TurboConfig.isInitialized()) {
            return false;
        }
        
        TurboConfig config = TurboConfig.getInstance();
        
        // Always enable in FULL_LRF mode
        String conversionMode = config.getString("storage.conversion-mode", "manual");
        if (ConversionMode.FULL_LRF.equals(ConversionMode.fromString(conversionMode))) {
            return true;
        }
        
        return config.getBoolean("storage.batch.enabled", true) ||
               config.getBoolean("storage.mmap.enabled", true) ||
               config.getBoolean("storage.integrity.enabled", true);
    }
    
    /**
     * Hook into RegionFileStorage to intercept creation.
     */
    private static void hookRegionFileStorage() {
        System.out.println("[TurboMC][Hooks] RegionFileStorage hook active - IOWorker integration handles this");
    }
    
    /**
     * Hook into IOWorker to intercept creation.
     */
    private static void hookIOWorker() {
        System.out.println("[TurboMC][Hooks] IOWorker hook active - direct integration in IOWorker constructor");
    }
    
    // Reflection helper methods
    
    @Nullable
    private static Path extractStorageFolder(RegionFileStorage storage) {
        try {
            Field folderField = RegionFileStorage.class.getDeclaredField("folder");
            folderField.setAccessible(true);
            return (Path) folderField.get(storage);
        } catch (Exception e) {
            return null;
        }
    }
    
    private static boolean extractSyncFlag(RegionFileStorage storage) {
        try {
            Field syncField = RegionFileStorage.class.getDeclaredField("sync");
            syncField.setAccessible(true);
            return syncField.getBoolean(storage);
        } catch (Exception e) {
            return true; // Default to sync
        }
    }
    
    @Nullable
    private static RegionStorageInfo extractStorageInfo(RegionFileStorage storage) {
        try {
            Field infoField = RegionFileStorage.class.getDeclaredField("info");
            infoField.setAccessible(true);
            return (RegionStorageInfo) infoField.get(storage);
        } catch (Exception e) {
            return null;
        }
    }
    
    @Nullable
    private static Path extractWorkerFolder(IOWorker worker) {
        try {
            // Get the storage field from IOWorker
            Field storageField = IOWorker.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            RegionFileStorage storage = (RegionFileStorage) storageField.get(worker);
            return extractStorageFolder(storage);
        } catch (Exception e) {
            return null;
        }
    }
    
    private static boolean extractWorkerSyncFlag(IOWorker worker) {
        try {
            Field storageField = IOWorker.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            RegionFileStorage storage = (RegionFileStorage) storageField.get(worker);
            return extractSyncFlag(storage);
        } catch (Exception e) {
            return true; // Default to sync
        }
    }
    
    @Nullable
    private static RegionStorageInfo extractWorkerStorageInfo(IOWorker worker) {
        try {
            Field storageField = IOWorker.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            RegionFileStorage storage = (RegionFileStorage) storageField.get(worker);
            return extractStorageInfo(storage);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Get comprehensive statistics from all TurboMC components.
     */
    public static TurboStorageManager.StorageManagerStats getGlobalStats() {
        TurboStorageManager manager = TurboStorageManager.getInstance();
        return manager.getStats();
    }
    
    /**
     * Force cleanup of all TurboMC wrappers for a specific storage object.
     */
    public static void cleanupWrapper(Object vanillaStorage) {
        TurboRegionFileStorage turboStorage = turboStorageWrappers.remove(vanillaStorage);
        if (turboStorage != null) {
            try {
                turboStorage.close();
            } catch (Exception e) {
                System.err.println("[TurboMC][Hooks] Error closing Turbo storage wrapper: " + e.getMessage());
            }
        }
        
        TurboIOWorker turboWorker = turboWorkerWrappers.remove(vanillaStorage);
        if (turboWorker != null) {
            try {
                turboWorker.close();
            } catch (Exception e) {
                System.err.println("[TurboMC][Hooks] Error closing Turbo worker wrapper: " + e.getMessage());
            }
        }
    }
    
    /**
     * Force cleanup of all TurboMC wrappers.
     * Call this during server shutdown.
     */
    public static void cleanupAll() {
        System.out.println("[TurboMC][Hooks] Cleaning up all TurboMC wrappers...");
        
        for (TurboRegionFileStorage storage : turboStorageWrappers.values()) {
            try {
                storage.close();
            } catch (Exception e) {
                System.err.println("[TurboMC][Hooks] Error closing Turbo storage: " + e.getMessage());
            }
        }
        
        for (TurboIOWorker worker : turboWorkerWrappers.values()) {
            try {
                worker.close();
            } catch (Exception e) {
                System.err.println("[TurboMC][Hooks] Error closing Turbo worker: " + e.getMessage());
            }
        }
        
        turboStorageWrappers.clear();
        turboWorkerWrappers.clear();
        
        try {
            TurboStorageManager.getInstance().close();
        } catch (Exception e) {
            System.err.println("[TurboMC][Hooks] Error closing storage manager: " + e.getMessage());
        }
        
        System.out.println("[TurboMC][Hooks] Cleanup completed. Final stats: " + getGlobalStats());
    }
    
    /**
     * Check if hooks are installed.
     */
    public static boolean areHooksInstalled() {
        return hooksInstalled.get();
    }
    
    /**
     * Get the number of active TurboMC wrappers.
     */
    public static int getActiveWrapperCount() {
        return turboStorageWrappers.size() + turboWorkerWrappers.size();
    }
}
