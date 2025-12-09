package com.turbomc.storage;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

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

    protected TurboIOWorker(RegionStorageInfo info, Path folder, boolean sync) {
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
                BitSet bitSet = this.getOrCreateOldDataForRegion(regionX, regionZ).join();
                if (!bitSet.isEmpty()) {
                    return true;
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
                BitSet bitSet2 = this.getOrCreateOldDataForRegion(regionX, regionZ).join();
                bitSet.or(bitSet2);
            }
        }

        return CompletableFuture.completedFuture(bitSet);
    }

    private CompletableFuture<BitSet> getOrCreateOldDataForRegion(int regionX, int regionZ) {
        long l = ChunkPos.asLong(regionX, regionZ);
        return this.regionCacheForBlender.computeIfAbsent(l, (m) -> {
            BitSet bitSet = new BitSet(1024);
            this.run(Priority.LOW, () -> {
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

    public CompletableFuture<Void> store(ChunkPos chunkPos, CompoundTag nbt) {
        PendingStore pendingStore = new PendingStore(nbt);
        return this.run(Priority.HIGH, () -> {
            PendingStore pendingStore2 = this.pendingWrites.put(chunkPos, pendingStore);
            if (pendingStore2 != null) {
                pendingStore2.promise.complete(Unit.INSTANCE);
            }
            return null;
        }).thenCompose((object) -> pendingStore.promise.thenApply(unit -> null));
    }

    public CompletableFuture<Optional<CompoundTag>> load(ChunkPos chunkPos) {
        return this.run(Priority.NORMAL, () -> {
            PendingStore pendingStore = this.pendingWrites.get(chunkPos);
            if (pendingStore != null) {
                return Optional.of(pendingStore.data);
            } else {
                try {
                    CompoundTag compoundTag = this.storage.read(chunkPos);
                    return compoundTag != null ? Optional.of(compoundTag) : Optional.empty();
                } catch (Exception var4) {
                    LOGGER.error("Failed to read chunk {}", chunkPos, var4);
                    return Optional.empty();
                }
            }
        });
    }

    public CompletableFuture<Void> synchronize(boolean fullSync) {
        return this.run(fullSync ? Priority.HIGH : Priority.NORMAL, () -> {
            try {
                this.storage.flush();
            } catch (IOException e) {
                throw new RuntimeException("Failed to flush storage", e);
            }
            return null;
        });
    }

    public CompletableFuture<Void> closeStore() throws IOException {
        return this.run(Priority.HIGH, () -> {
            this.shutdownRequested.set(true);
            try {
                this.storage.close();
            } catch (IOException e) {
                throw new RuntimeException("Failed to close storage", e);
            }
            return null;
        });
    }
    
    @Override
    public void close() throws IOException {
        this.shutdownRequested.set(true);
        this.consecutiveExecutor.close();
        try {
            this.storage.close();
        } catch (IOException e) {
            throw e;
        }
    }

    private <T> CompletableFuture<T> run(Priority priority, Supplier<T> task) {
        if (this.shutdownRequested.get()) {
            return CompletableFuture.completedFuture(null);
        } else {
            CompletableFuture<T> completableFuture = new CompletableFuture<>();
            this.consecutiveExecutor.schedule(new StrictQueue.RunnableWithPriority(priority.ordinal(), () -> {
                try {
                    completableFuture.complete(task.get());
                } catch (Throwable var3) {
                    completableFuture.completeExceptionally(var3);
                }
            }));
            return completableFuture;
        }
    }

    public CompletableFuture<Void> scanChunk(ChunkPos chunkPos, StreamTagVisitor streamTagVisitor) {
        return this.run(Priority.NORMAL, () -> {
            try {
                CompoundTag compoundTag = this.storage.read(chunkPos);
                if (compoundTag != null) {
                    compoundTag.accept(streamTagVisitor);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return null;
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

    static enum Priority {
        LOW,
        NORMAL,
        HIGH;

        private Priority() {
        }
    }

    static class PendingStore {
        final CompoundTag data;
        final CompletableFuture<Unit> promise;

        PendingStore(CompoundTag nbt) {
            this.data = nbt;
            this.promise = new CompletableFuture<>();
        }
    }
}
