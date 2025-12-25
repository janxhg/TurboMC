package com.turbomc.storage.batch;

import com.turbomc.compression.TurboCompressionService;
import com.turbomc.storage.lrf.LRFChunkEntry;
import com.turbomc.storage.lrf.LRFConstants;
import com.turbomc.storage.lrf.LRFRegionWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import com.turbomc.storage.optimization.SharedRegionResource;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.ChunkPos;

/**
 * High-performance batch chunk saver for LRF regions.
 * Provides efficient concurrent chunk writing with compression and integrity verification.
 * 
 * Features:
 * - Batch compression using multiple threads
 * - Asynchronous write operations
 * - Memory-efficient buffering
 * - Progress tracking and statistics
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class ChunkBatchSaver implements AutoCloseable {
    
    private final Path regionPath;
    private final ExecutorService compressionExecutor;
    private final ExecutorService writeExecutor;
    private final List<LRFChunkEntry> pendingChunks;
    private final List<CompletableFuture<Void>> chunkFutures;
    private final AtomicBoolean isClosed;
    private final AtomicInteger chunksSaved;
    private final AtomicInteger chunksCompressed;
    private final ConcurrentHashMap<Long, LRFChunkEntry> inflightChunks;
    private final long startTime;
    private final boolean isSharedPool;
    private final SharedRegionResource sharedResource;
    private final com.turbomc.storage.mmap.FlushBarrier flushBarrier;
    
    // Persistent writer
    private LRFRegionWriter regionWriter;
    private long lastForceTime;
    
    // Configuration
    private final int compressionThreads;
    private final int writeThreads;
    private final int batchSize;
    private final int compressionType;
    private final long autoFlushDelayMs;
    private volatile long lastFlushTime;
    private java.util.function.Consumer<List<LRFChunkEntry>> postFlushAction;
    
    /**
     * Create a new batch saver with default configuration.
     * 
     * @param regionPath Path to the LRF region file
     * @param compressionType Compression algorithm to use
     */
    public ChunkBatchSaver(SharedRegionResource resource, int compressionType) {
        this(resource, compressionType, 
             Runtime.getRuntime().availableProcessors() / 2,
             1, // Force single writer thread per region to prevent corruption
             64, 
             100L); // Default 100ms delay
    }
    
    /**
     * Create a new batch saver with custom configuration.
     * 
     * @param regionPath Path to the LRF region file
     * @param compressionType Compression algorithm to use
     * @param compressionThreads Number of compression threads
     * @param writeThreads Number of write threads
     * @param batchSize Maximum chunks per batch
     */
    /**
     * Create a new batch saver with shared executors.
     * 
     * @param regionPath Path to the LRF region file
     * @param compressionType Compression algorithm to use
     * @param compressionExecutor Shared compression executor
     * @param writeExecutor Shared write executor
     * @param batchSize Maximum chunks per batch
     */
    public ChunkBatchSaver(Path regionPath, int compressionType, 
                          ExecutorService compressionExecutor, ExecutorService writeExecutor, int batchSize) throws IOException {
        this(new SharedRegionResource(regionPath), compressionType, compressionExecutor, writeExecutor, batchSize, 500L);
    }

    public ChunkBatchSaver(SharedRegionResource resource, int compressionType, 
                          ExecutorService compressionExecutor, ExecutorService writeExecutor, 
                          int batchSize, long autoFlushDelayMs) {
        this.regionPath = resource.getPath();
        this.sharedResource = resource;
        this.compressionType = compressionType;
        this.compressionExecutor = compressionExecutor;
        this.writeExecutor = writeExecutor;
        this.batchSize = batchSize;
        this.compressionThreads = -1; // Shared pool
        this.writeThreads = -1;       // Shared pool
        
        this.autoFlushDelayMs = autoFlushDelayMs; 
        
        this.lastFlushTime = System.currentTimeMillis();
        this.lastForceTime = System.currentTimeMillis();
        this.isSharedPool = true;
        
        this.pendingChunks = new ArrayList<>(batchSize);
        this.chunkFutures = new ArrayList<>();
        this.isClosed = new AtomicBoolean(false);
        this.chunksSaved = new AtomicInteger(0);
        this.chunksCompressed = new AtomicInteger(0);
        this.inflightChunks = new ConcurrentHashMap<>();
        this.startTime = System.currentTimeMillis();
        this.flushBarrier = new com.turbomc.storage.mmap.FlushBarrier(false); // Non-verbose
        
        if (LRFRegionWriter.isVerbose()) {
            System.out.println("[TurboMC] ChunkBatchSaver initialized (SharedPools): " + regionPath.getFileName() +
                             " (compression: " + LRFConstants.getCompressionName(compressionType) +
                             ", batch size: " + batchSize + ", delay: " + autoFlushDelayMs + "ms)");
        }
    }
    
    /**
     * Create a new batch saver with custom configuration including auto-flush delay.
     * @deprecated Use constructor with shared executors
     */
    @Deprecated
    public ChunkBatchSaver(SharedRegionResource resource, int compressionType, 
                          int compressionThreads, int writeThreads, int batchSize, long autoFlushDelayMs) {
        this.regionPath = resource.getPath();
        this.sharedResource = resource;
        this.compressionType = compressionType;
        this.batchSize = batchSize;
        this.autoFlushDelayMs = autoFlushDelayMs;
        this.lastFlushTime = System.currentTimeMillis();
        this.compressionThreads = compressionThreads;
        this.writeThreads = writeThreads;
        
        this.compressionExecutor = Executors.newFixedThreadPool(compressionThreads, r -> {
            Thread t = new Thread(r, "ChunkBatchSaver-Compression-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        
        this.writeExecutor = Executors.newFixedThreadPool(writeThreads, r -> {
            Thread t = new Thread(r, "ChunkBatchSaver-Writer-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        
        this.pendingChunks = new ArrayList<>(batchSize);
        this.chunkFutures = new ArrayList<>();
        this.isClosed = new AtomicBoolean(false);
        this.chunksSaved = new AtomicInteger(0);
        this.chunksCompressed = new AtomicInteger(0);
        this.inflightChunks = new ConcurrentHashMap<>();
        this.startTime = System.currentTimeMillis();
        this.isSharedPool = false;
        this.flushBarrier = new com.turbomc.storage.mmap.FlushBarrier(false);
    }
    
    /**
     * Add a chunk to the batch queue.
     * If the batch is full, it will be automatically flushed.
     * 
     * @param chunk Chunk entry to save
     * @return CompletableFuture that completes when the chunk is saved
     * @throws IllegalStateException if saver is closed
     */
    public CompletableFuture<Void> saveChunk(LRFChunkEntry chunk) {
        if (isClosed.get()) {
            throw new IllegalStateException("ChunkBatchSaver is closed");
        }
        
        CompletableFuture<Void> completionFuture = new CompletableFuture<>();
        
        // Track chunk as in-flight
        inflightChunks.put(ChunkPos.asLong(chunk.getChunkX(), chunk.getChunkZ()), chunk);
        
        synchronized (pendingChunks) {
            pendingChunks.add(chunk);
            chunkFutures.add(completionFuture);
            
            if (pendingChunks.size() >= batchSize) {
                flushBatch();
            } else if (pendingChunks.size() == 1) {
                // First chunk in batch, schedule a flush after delay
                CompletableFuture.delayedExecutor(autoFlushDelayMs, TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        // Double-check if we still need to flush after the delay
                        if (System.currentTimeMillis() - lastFlushTime >= autoFlushDelayMs) {
                            flushBatch();
                        }
                    });
            }
        }
        return completionFuture;
    }
    
    /**
     * Add a chunk with raw NBT data.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param nbtData Uncompressed NBT data
     * @return CompletableFuture that completes when the chunk is saved
     */
    public CompletableFuture<Void> saveChunk(int chunkX, int chunkZ, byte[] nbtData) {
        return saveChunk(new LRFChunkEntry(chunkX, chunkZ, nbtData));
    }
    
    /**
     * Add multiple chunks to the batch queue.
     * 
     * @param chunks List of chunks to save
     * @return CompletableFuture that completes when all chunks are saved
     */
    public CompletableFuture<Void> saveChunks(List<LRFChunkEntry> chunks) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (LRFChunkEntry chunk : chunks) {
            futures.add(saveChunk(chunk));
        }
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }
    
    /**
     * Flush the current batch of chunks.
     * 
     * @return CompletableFuture that completes when the batch is saved
     */
    public CompletableFuture<Void> flushBatch() {
        List<LRFChunkEntry> currentBatch;
        List<CompletableFuture<Void>> currentFutures;
        
        synchronized (pendingChunks) {
            if (pendingChunks.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            
            currentBatch = new ArrayList<>(pendingChunks);
            currentFutures = new ArrayList<>(chunkFutures);
            pendingChunks.clear();
            chunkFutures.clear();
            lastFlushTime = System.currentTimeMillis();
        }
        
        return CompletableFuture.completedFuture(null)
            .thenComposeAsync(v -> compressBatch(currentBatch), compressionExecutor)
            .thenComposeAsync(compressedChunks -> writeBatch(compressedChunks), writeExecutor)
            .whenComplete((result, throwable) -> {
                // Clean up inflight tracking
                for (LRFChunkEntry chunk : currentBatch) {
                    inflightChunks.remove(ChunkPos.asLong(chunk.getChunkX(), chunk.getChunkZ()));
                }
                
                if (throwable != null) {
                    System.err.println("[TurboMC] Error saving batch: " + throwable.getMessage());
                    for (CompletableFuture<Void> f : currentFutures) f.completeExceptionally(throwable);
                } else {
                    chunksSaved.addAndGet(currentBatch.size());
                    
                    // Trigger post-flush action (integrity updates, cache invalidation, etc.)
                    if (postFlushAction != null) {
                        try {
                            postFlushAction.accept(currentBatch);
                        } catch (Exception e) {
                            System.err.println("[TurboMC] Error in post-flush action: " + e.getMessage());
                        }
                    }
                    
                    for (CompletableFuture<Void> f : currentFutures) f.complete(null);
                }
            });
    }
    
    /**
     * Compress a batch of chunks in parallel (fully async).
     */
    private CompletableFuture<List<CompressedChunk>> compressBatch(List<LRFChunkEntry> chunks) {
        List<CompletableFuture<CompressedChunk>> futures = new ArrayList<>();
        
        for (LRFChunkEntry chunk : chunks) {
            CompletableFuture<CompressedChunk> future = CompletableFuture
                .supplyAsync(() -> compressChunk(chunk), compressionExecutor);
            futures.add(future);
        }
        
        // Wait for all compression to complete asynchronously
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                List<CompressedChunk> compressed = new ArrayList<>(chunks.size());
                for (CompletableFuture<CompressedChunk> future : futures) {
                    try {
                        CompressedChunk result = future.get();
                        if (result != null) {
                            compressed.add(result);
                            chunksCompressed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        System.err.println("[TurboMC] Error compressing chunk: " + e.getMessage());
                        // Continue with other chunks
                    }
                }
                return compressed;
            });
    }
    
    /**
     * Compress a single chunk.
     */
    private CompressedChunk compressChunk(LRFChunkEntry chunk) {
        byte[] data = chunk.getData();
        
        // Append timestamp to data
        ByteBuffer dataWithTimestamp = ByteBuffer.allocate(data.length + 8);
        dataWithTimestamp.put(data);
        dataWithTimestamp.putLong(chunk.getTimestamp());
        dataWithTimestamp.flip();
        
        byte[] dataToWrite = new byte[dataWithTimestamp.remaining()];
        dataWithTimestamp.get(dataToWrite);
        
        // Compress if needed
        byte[] compressedData;
        if (compressionType == LRFConstants.COMPRESSION_NONE) {
            compressedData = dataToWrite;
        } else {
            try {
                compressedData = TurboCompressionService.getInstance().compress(dataToWrite);
            } catch (Exception e) {
                System.err.println("[TurboMC] Compression failed for chunk: " + e.getMessage());
                compressedData = dataToWrite; // Fallback to uncompressed
            }
        }
        
        return new CompressedChunk(chunk, compressedData);
    }
    
    /**
     * Write a batch of compressed chunks to file.
     */
    private synchronized CompletableFuture<Void> writeBatch(List<CompressedChunk> compressedChunks) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Initialize writer lazily
                if (regionWriter == null) {
                    regionWriter = new LRFRegionWriter(sharedResource, compressionType);
                }
                
                // Write chunks sequentially - already synchronized in LRFRegionWriter, 
                // but we stay synchronized here to ensure flush() follows immediately
                for (CompressedChunk compressedChunk : compressedChunks) {
                    regionWriter.addChunk(compressedChunk.originalChunk);
                }
                
                // Smart flushing: Only force fsync if 2 seconds passed OR batch is large
                boolean shouldForce = (System.currentTimeMillis() - lastForceTime > 2000) || 
                                     (compressedChunks.size() >= batchSize / 2);
                
                // Acquire write lock before flushing to disk
                flushBarrier.beforeFlush(regionPath);
                
                try {
                    regionWriter.flush(shouldForce);
                    
                    if (shouldForce) {
                        lastForceTime = System.currentTimeMillis();
                    }
                } finally {
                    // Release lock after flush, forcing buffer to disk
                    flushBarrier.afterFlush(regionPath, null);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to write batch to " + regionPath, e);
            }
        }, writeExecutor);
    }
    
    /**
     * Wait for all pending operations to complete.
     * 
     * @param timeout Maximum time to wait
     * @param unit Time unit
     * @return True if all operations completed, false if timeout
     */
    public boolean awaitCompletion(long timeout, TimeUnit unit) {
        try {
            flushBatch().get(timeout, unit);
            
            // Wait for all write futures
            for (CompletableFuture<Void> future : chunkFutures) {
                future.get(timeout, unit);
            }
            
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Check if a chunk is currently in the batch or being written.
     */
    public boolean hasPendingChunk(int chunkX, int chunkZ) {
        return inflightChunks.containsKey(ChunkPos.asLong(chunkX, chunkZ));
    }

    /**
     * Get a pending or in-flight chunk if available.
     * Allows "read-your-writes" consistency.
     */
    public LRFChunkEntry getPendingChunk(int chunkX, int chunkZ) {
        return inflightChunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    public void setPostFlushAction(java.util.function.Consumer<List<LRFChunkEntry>> action) {
        this.postFlushAction = action;
    }

    /**
     * Get statistics about the saver performance.
     */
    public BatchSaverStats getStats() {
        long elapsed = System.currentTimeMillis() - startTime;
        int saved = chunksSaved.get();
        int compressed = chunksCompressed.get();
        
        return new BatchSaverStats(
            saved, compressed, elapsed,
            saved > 0 ? (double) elapsed / saved : 0,
            compressionThreads, writeThreads
        );
    }
    
    /**
     * Get number of pending chunks.
     */
    public int getPendingCount() {
        synchronized (pendingChunks) {
            return pendingChunks.size();
        }
    }
    
    /**
     * Check if saver is closed.
     */
    public boolean isClosed() {
        return isClosed.get();
    }
    
    @Override
    public void close() throws IOException {
        if (isClosed.compareAndSet(false, true)) {
            try {
                // Flush remaining chunks with a mandatory fsync
                if (regionWriter != null) {
                    flushBatch().get(10, TimeUnit.SECONDS);
                    regionWriter.flush(true);
                    regionWriter.close();
                    regionWriter = null;
                }
                
                // Shutdown executors only if not shared
                if (!isSharedPool) {
                    compressionExecutor.shutdown();
                    writeExecutor.shutdown();
                    
                    if (!compressionExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                        compressionExecutor.shutdownNow();
                    }
                    
                    if (!writeExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                        writeExecutor.shutdownNow();
                    }
                }
                
                System.out.println("[TurboMC] ChunkBatchSaver closed: " + getStats());
            } catch (Exception e) {
                throw new IOException("Error closing ChunkBatchSaver", e);
            }
        }
    }
    
    /**
     * Container for compressed chunk data.
     */
    private static class CompressedChunk {
        final LRFChunkEntry originalChunk;
        final byte[] compressedData;
        
        CompressedChunk(LRFChunkEntry originalChunk, byte[] compressedData) {
            this.originalChunk = originalChunk;
            this.compressedData = compressedData;
        }
    }
    
    /**
     * Statistics for batch saver performance.
     */
    public static class BatchSaverStats {
        private final int chunksSaved;
        private final int chunksCompressed;
        private final long elapsedMs;
        private final double avgTimePerChunk;
        private final int compressionThreads;
        private final int writeThreads;
        
        BatchSaverStats(int chunksSaved, int chunksCompressed, long elapsedMs,
                       double avgTimePerChunk, int compressionThreads, int writeThreads) {
            this.chunksSaved = chunksSaved;
            this.chunksCompressed = chunksCompressed;
            this.elapsedMs = elapsedMs;
            this.avgTimePerChunk = avgTimePerChunk;
            this.compressionThreads = compressionThreads;
            this.writeThreads = writeThreads;
        }
        
        public int getChunksSaved() { return chunksSaved; }
        public int getChunksCompressed() { return chunksCompressed; }
        public long getElapsedMs() { return elapsedMs; }
        public double getAvgTimePerChunk() { return avgTimePerChunk; }
        public int getCompressionThreads() { return compressionThreads; }
        public int getWriteThreads() { return writeThreads; }
        
        @Override
        public String toString() {
            return String.format("BatchSaverStats{saved=%d, compressed=%d, elapsed=%dms, avg=%.2fms/chunk, threads=%d+%d}",
                    chunksSaved, chunksCompressed, elapsedMs, avgTimePerChunk, compressionThreads, writeThreads);
        }
    }
}
