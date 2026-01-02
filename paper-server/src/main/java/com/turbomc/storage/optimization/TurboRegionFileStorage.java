package com.turbomc.storage.optimization;

import com.turbomc.performance.TurboOptimizerModule;
import com.turbomc.config.TurboConfig;
import com.turbomc.storage.lrf.LRFRegionFileAdapter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;

/**
 * TurboRegionFileStorage (Functional Implementation)
 * Replaces vanilla RegionFileStorage to support LRF and optimizations.
 */
public class TurboRegionFileStorage implements TurboOptimizerModule {

    // Wrapper class to handle reference counting for safe concurrency
    protected static class CachedRegion {
        final RegionFile file;
        private int usages = 0;
        private boolean pendingClose = false;

        CachedRegion(RegionFile file) {
            this.file = file;
        }

        synchronized void increment() {
            usages++;
        }

        synchronized void decrement() {
            usages--;
            if (usages <= 0 && pendingClose) {
                closeActual();
            }
        }

        synchronized void markForClose() {
            pendingClose = true;
            if (usages <= 0) {
                closeActual();
            }
        }

        private void closeActual() {
            try {
                file.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static volatile TurboRegionFileStorage instance;
    private final RegionStorageInfo info;
    private final Path folder;
    private final boolean sync;
    private final Long2ObjectLinkedOpenHashMap<CachedRegion> regionCache = new Long2ObjectLinkedOpenHashMap<>();
    private int maxCacheSize = 256;

    public static TurboRegionFileStorage getInstance() {
        return instance; 
    }

    public TurboRegionFileStorage(RegionStorageInfo info, Path folder, boolean sync) {
        this.info = info;
        this.folder = folder;
        this.sync = sync;
        this.maxCacheSize = TurboConfig.getInstance().getMaxCacheSize();
        instance = this; 
    }
    
    public TurboRegionFileStorage() {
        this(null, null, false); 
    }

    @Override
    public void loadConfiguration(TurboConfig config) {
        this.maxCacheSize = config.getMaxCacheSize();
    }

    private CachedRegion getRegionFile(ChunkPos chunkPos, boolean existingOnly) throws IOException {
        long packedPos = ChunkPos.asLong(chunkPos.getRegionX(), chunkPos.getRegionZ());
        
        synchronized (regionCache) {
            CachedRegion cached = regionCache.getAndMoveToFirst(packedPos);
            if (cached != null) {
                cached.increment();
                return cached;
            }

            if (regionCache.size() >= maxCacheSize) {
                CachedRegion removed = regionCache.removeLast();
                if (removed != null) removed.markForClose();
            }

            Files.createDirectories(folder);
            
            String format = TurboConfig.getInstance().getStorageFormat();
            boolean useLRF = "lrf".equalsIgnoreCase(format);
            String ext = useLRF ? ".lrf" : ".mca";
            
            Path path = folder.resolve("r." + chunkPos.getRegionX() + "." + chunkPos.getRegionZ() + ext);
            
            if (existingOnly && !Files.exists(path)) {
                return null;
            }

            RegionFile regionFile;
            if (useLRF) {
                regionFile = new LRFRegionFileAdapter(info, path);
            } else {
                regionFile = new RegionFile(info, path, folder, sync);
            }
            
            cached = new CachedRegion(regionFile);
            cached.increment(); // For the caller
            regionCache.putAndMoveToFirst(packedPos, cached);
            return cached;
        }
    }

    public void scanRegion(int regionX, int regionZ, java.util.function.BiFunction<Integer, Integer, Boolean> callback) {
        CachedRegion cached = null;
        try {
            ChunkPos pos = new ChunkPos(regionX << 5, regionZ << 5); 
            cached = getRegionFile(pos, true);
            if (cached == null) return;
            
            RegionFile file = cached.file;
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    ChunkPos cp = new ChunkPos(regionX * 32 + x, regionZ * 32 + z);
                    if (file.hasChunk(cp)) {
                        if (!callback.apply(cp.x, cp.z)) return;
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (cached != null) cached.decrement();
        }
    }

    public void write(ChunkPos pos, CompoundTag nbt) throws IOException {
        CachedRegion cached = getRegionFile(pos, false);
        try {
            try (DataOutputStream out = cached.file.getChunkDataOutputStream(pos)) {
                NbtIo.write(nbt, out);
            }
        } finally {
            cached.decrement();
        }
    }

    public CompoundTag read(ChunkPos pos) throws IOException {
        CachedRegion cached = getRegionFile(pos, true);
        if (cached == null) return null;
        
        try {
            try (DataInputStream in = cached.file.getChunkDataInputStream(pos)) {
                if (in == null) return null;
                return NbtIo.read(in);
            }
        } finally {
            cached.decrement();
        }
    }

    public void flush() throws IOException {
        synchronized (regionCache) {
            for (CachedRegion cached : regionCache.values()) {
                try {
                    cached.file.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void close() throws IOException {
        synchronized (regionCache) {
            for (CachedRegion cached : regionCache.values()) {
                cached.markForClose();
            }
            regionCache.clear();
        }
    }

    public RegionStorageInfo info() { return info; }

    public com.turbomc.storage.optimization.TurboStorageManager.StorageManagerStats getTurboStats() { 
        return com.turbomc.storage.optimization.TurboStorageManager.getInstance().getStats(); 
    }

    public java.util.concurrent.CompletableFuture<java.util.List<com.turbomc.storage.integrity.ChunkIntegrityValidator.IntegrityReport>> validateRegion(int regionX, int regionZ) { 
        return java.util.concurrent.CompletableFuture.completedFuture(java.util.Collections.emptyList()); 
    }

    @Override
    public void initialize() {}

    @Override
    public void start() {}

    @Override
    public void stop() {
        try {
            close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean isEnabled() { return true; }

    @Override
    public String getModuleName() { return "TurboRegionFileStorage"; }

    @Override
    public String getPerformanceStats() { return "Active: " + regionCache.size() + " files"; }

    @Override
    public boolean shouldOptimize() { return true; }

    @Override
    public void performOptimization() {}
}
