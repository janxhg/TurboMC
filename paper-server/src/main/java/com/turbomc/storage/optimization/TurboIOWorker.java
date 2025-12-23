package com.turbomc.storage.optimization;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import com.turbomc.storage.integrity.ChunkIntegrityValidator;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.PriorityConsecutiveExecutor;
import net.minecraft.util.thread.StrictQueue;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.ChunkScanAccess;

import org.slf4j.Logger;

/**
 * TurboMC-enhanced IOWorker that uses TurboRegionFileStorage for all chunk operations.
 * This provides seamless integration with TurboMC's advanced storage features while maintaining
 * full compatibility with Paper's chunk system.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class TurboIOWorker implements ChunkScanAccess, AutoCloseable {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();
    private final PriorityConsecutiveExecutor consecutiveExecutor;
    public final TurboRegionFileStorage storage; // Enhanced storage
    private final SequencedMap<ChunkPos, PendingStore> pendingWrites = new LinkedHashMap<>();
    private final Long2ObjectLinkedOpenHashMap<CompletableFuture<BitSet>> regionCacheForBlender = new Long2ObjectLinkedOpenHashMap<>();
    private static final int REGION_CACHE_SIZE = 1024;

    public TurboIOWorker(RegionStorageInfo info, Path folder, boolean sync) {
        // Use TurboRegionFileStorage instead of vanilla RegionFileStorage
        this.storage = new TurboRegionFileStorage(info, folder, sync);
        this.consecutiveExecutor = new PriorityConsecutiveExecutor(Priority.values().length, Util.ioPool(), "TurboIOWorker-" + info.type());
        
        LOGGER.info("[TurboMC][IOWorker] Turbo IOWorker initialized for: " + info.type());
    }

    public boolean isOldChunkAround(ChunkPos chunkPos, int radius) {
        ChunkPos chunkPos1 = new ChunkPos(chunkPos.x - radius, chunkPos.z - radius);
        ChunkPos chunkPos2 = new ChunkPos(chunkPos.x + radius, chunkPos.z + radius);

        for (int regionX = chunkPos1.getRegionX(); regionX <= chunkPos2.getRegionX(); regionX++) {
            for (int regionZ = chunkPos1.getRegionZ(); regionZ <= chunkPos2.getRegionZ(); regionZ++) {
                try {
                    BitSet bitSet = this.getOrCreateOldDataForRegion(regionX, regionZ).get(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (!bitSet.isEmpty()) {
                        return true;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to check old chunk region {} {}", regionX, regionZ, e);
                }
            }
        }

        return false;
    }

    public CompletableFuture<BitSet> getOldChunksAround(ChunkPos chunkPos, int radius) {
        ChunkPos chunkPos1 = new ChunkPos(chunkPos.x - radius, chunkPos.z - radius);
        ChunkPos chunkPos2 = new ChunkPos(chunkPos.x + radius, chunkPos.z + radius);
        BitSet bitSet = new BitSet();

        for (int regionX = chunkPos1.getRegionX(); regionX <= chunkPos2.getRegionX(); regionX++) {
            for (int regionZ = chunkPos1.getRegionZ(); regionZ <= chunkPos2.getRegionZ(); regionZ++) {
                try {
                    BitSet bitSet2 = this.getOrCreateOldDataForRegion(regionX, regionZ).get(5, java.util.concurrent.TimeUnit.SECONDS);
                    bitSet.or(bitSet2);
                } catch (Exception e) {
                    LOGGER.warn("Failed to get old chunks around region {} {}", regionX, regionZ, e);
                }
            }
        }

        return CompletableFuture.completedFuture(bitSet);
    }

    private CompletableFuture<BitSet> getOrCreateOldDataForRegion(int regionX, int regionZ) {
        long l = ChunkPos.asLong(regionX, regionZ);
        return this.regionCacheForBlender.computeIfAbsent(l, (m) -> {
            BitSet bitSet = new BitSet(1024);
            this.run(Priority.BACKGROUND, () -> {
                try {
                    this.storage.scanRegion(regionX, regionZ, (chunkX, chunkZ) -> {
                        bitSet.set((int) ChunkPos.asLong(chunkX, chunkZ));
                        return true;
                    });
                } catch (Exception var6) {
                    LOGGER.warn("Failed to scan region {}", new ChunkPos(regionX, regionZ), var6);
                }

                return Unit.INSTANCE;
            });
            return CompletableFuture.completedFuture(bitSet);
        });
    }

    public CompletableFuture<Void> store(ChunkPos chunkPos, @Nullable CompoundTag chunkData) {
        return this.store(chunkPos, () -> chunkData);
    }

    public CompletableFuture<Void> store(ChunkPos chunkPos, Supplier<CompoundTag> dataSupplier) {
        PendingStore pendingStore = new PendingStore(dataSupplier.get());
        PendingStore pendingStore2 = this.pendingWrites.put(chunkPos, pendingStore);
        if (pendingStore2 != null) {
            try {
                this.writeData(chunkPos, pendingStore2);
            } catch (Exception var4) {
                LOGGER.error("Failed to store chunk {}", chunkPos, var4);
                pendingStore2.result.completeExceptionally(var4);
            }
        }

        return this.submitTask(() -> {
            try {
                this.writeData(chunkPos, pendingStore);
            } catch (Exception var4) {
                LOGGER.error("Failed to store chunk {}", chunkPos, var4);
                pendingStore.result.completeExceptionally(var4);
            }
            return null;
        }).thenCompose((object) -> pendingStore.result).thenApply(unit -> null);
    }

    private void writeData(ChunkPos chunkPos, PendingStore pendingStore) throws IOException {
        CompoundTag compoundTag = pendingStore.copyData();
        this.storage.write(chunkPos, compoundTag);
        pendingStore.result.complete(null);
    }

    public CompletableFuture<CompoundTag> load(ChunkPos chunkPos) {
        return this.run(Priority.FOREGROUND, () -> {
            PendingStore pendingStore = this.pendingWrites.get(chunkPos);
            if (pendingStore != null) {
                return pendingStore.copyData();
            } else {
                try {
                    return this.storage.read(chunkPos);
                } catch (Exception var4) {
                    LOGGER.error("Failed to read chunk {}", chunkPos, var4);
                    return null;
                }
            }
        });
    }

    public CompletableFuture<Void> synchronize(boolean fullSync) {
        return this.run(fullSync ? Priority.FOREGROUND : Priority.BACKGROUND, () -> {
            try {
                this.storage.flush();
            } catch (IOException e) {
                throw new RuntimeException("Failed to flush storage", e);
            }
            return null;
        });
    }

    public CompletableFuture<Void> closeStore() throws IOException {
        return this.run(Priority.SHUTDOWN, () -> {
            if (this.shutdownRequested.compareAndSet(false, true)) {
                this.waitForShutdown();
                
                try {
                    this.consecutiveExecutor.close();
                } catch (Exception e) {
                    LOGGER.error("Failed to close consecutive executor", e);
                }

                try {
                    this.storage.close();
                } catch (Exception var2) {
                    LOGGER.error("Failed to close storage", (Throwable)var2);
                }
            }
            return null;
        });
    }

    private void waitForShutdown() {
        this.consecutiveExecutor
            .scheduleWithResult(Priority.SHUTDOWN.ordinal(), completableFuture -> completableFuture.complete(Unit.INSTANCE))
            .join();
    }

    public RegionStorageInfo storageInfo() {
        return this.storage.info();
    }

    private <T> CompletableFuture<T> run(Priority priority, Supplier<T> task) {
        if (this.shutdownRequested.get()) {
            return CompletableFuture.completedFuture(null);
        } else {
            CompletableFuture<T> completableFuture = new CompletableFuture<>();
            
            // For PaperMC Moonrise compatibility, ensure critical operations run on main thread
            if (priority == Priority.FOREGROUND) {
                // Run critical operations synchronously on main thread
                try {
                    completableFuture.complete(task.get());
                } catch (Exception var3) {
                    completableFuture.completeExceptionally(var3);
                }
            } else {
                // Background operations can use the executor
                this.consecutiveExecutor.scheduleWithResult(priority.ordinal(), (future) -> {
                    try {
                        completableFuture.complete(task.get());
                    } catch (Exception var3) {
                        completableFuture.completeExceptionally(var3);
                    }
                });
            }
            return completableFuture;
        }
    }

    private <T> CompletableFuture<T> submitTask(Supplier<T> task) {
        return this.run(Priority.FOREGROUND, task);
    }

    public CompletableFuture<Void> scanChunk(ChunkPos chunkPos, StreamTagVisitor streamTagVisitor) {
        return this.run(Priority.BACKGROUND, () -> {
            try {
                CompoundTag compoundTag = this.storage.read(chunkPos);
                if (compoundTag != null) {
                    compoundTag.accept(streamTagVisitor);
                }
                return null;
            } catch (Exception var4) {
                LOGGER.error("Failed to scan chunk {}", chunkPos, var4);
                return null;
            }
        });
    }

    public boolean doesChunkExist(ChunkPos chunkPos) throws IOException {
        return this.storage.read(chunkPos) != null;
    }

    // Enhanced methods for TurboMC features
    
    /**
     * Get TurboMC storage statistics.
     */
    public TurboStorageManager.StorageManagerStats getTurboStats() {
        return this.storage.getTurboStats();
    }
    
    /**
     * Validate integrity of all chunks in a region.
     */
    public CompletableFuture<java.util.List<ChunkIntegrityValidator.IntegrityReport>> validateRegion(int regionX, int regionZ) {
        return this.storage.validateRegion(regionX, regionZ);
    }
    
    /**
     * Force flush all pending operations.
     */
    public void flush() throws IOException {
        this.storage.flush();
    }

    @Override
    public void close() throws IOException {
        this.closeStore().join();
    }

    static enum Priority {
        LOW,
        NORMAL,
        HIGH,
        BACKGROUND,
        FOREGROUND,
        SHUTDOWN;

        private Priority() {
        }
    }

    static class PendingStore {
        @Nullable
        CompoundTag data;
        final CompletableFuture<Void> result = new CompletableFuture<>();

        public PendingStore(@Nullable CompoundTag data) {
            this.data = data;
        }

        @Nullable
        CompoundTag copyData() {
            CompoundTag compoundTag = this.data;
            return compoundTag == null ? null : compoundTag.copy();
        }
    }
}
