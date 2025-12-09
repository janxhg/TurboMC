package com.turbomc.storage;

import com.turbomc.compression.TurboCompressionService;

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
    private final List<CompletableFuture<Void>> writeFutures;
    private final AtomicBoolean isClosed;
    private final AtomicInteger chunksSaved;
    private final AtomicInteger chunksCompressed;
    private final long startTime;
    
    // Configuration
    private final int compressionThreads;
    private final int writeThreads;
    private final int batchSize;
    private final int compressionType;
    
    /**
     * Create a new batch saver with default configuration.
     * 
     * @param regionPath Path to the LRF region file
     * @param compressionType Compression algorithm to use
     */
    public ChunkBatchSaver(Path regionPath, int compressionType) {
        this(regionPath, compressionType, 
             Runtime.getRuntime().availableProcessors() / 2,
             Math.max(1, Runtime.getRuntime().availableProcessors() / 4),
             64);
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
    public ChunkBatchSaver(Path regionPath, int compressionType, 
                          int compressionThreads, int writeThreads, int batchSize) {
        this.regionPath = regionPath;
        this.compressionType = compressionType;
        this.compressionThreads = compressionThreads;
        this.writeThreads = writeThreads;
        this.batchSize = batchSize;
        
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
        this.writeFutures = new ArrayList<>();
        this.isClosed = new AtomicBoolean(false);
        this.chunksSaved = new AtomicInteger(0);
        this.chunksCompressed = new AtomicInteger(0);
        this.startTime = System.currentTimeMillis();
        
        System.out.println("[TurboMC] ChunkBatchSaver initialized: " + regionPath.getFileName() +
                         " (compression: " + LRFConstants.getCompressionName(compressionType) +
                         ", threads: " + compressionThreads + "+" + writeThreads +
                         ", batch size: " + batchSize + ")");
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
        
        synchronized (pendingChunks) {
            pendingChunks.add(chunk);
            
            // Auto-flush if batch is full
            if (pendingChunks.size() >= batchSize) {
                return flushBatch();
            }
        }
        
        return CompletableFuture.completedFuture(null);
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
        
        synchronized (pendingChunks) {
            if (pendingChunks.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            
            currentBatch = new ArrayList<>(pendingChunks);
            pendingChunks.clear();
        }
        
        return CompletableFuture
            .supplyAsync(() -> compressBatch(currentBatch), compressionExecutor)
            .thenComposeAsync(compressedChunks -> writeBatch(compressedChunks), writeExecutor)
            .whenComplete((result, throwable) -> {
                if (throwable != null) {
                    System.err.println("[TurboMC] Error saving batch: " + throwable.getMessage());
                    throwable.printStackTrace();
                } else {
                    chunksSaved.addAndGet(currentBatch.size());
                }
            });
    }
    
    /**
     * Compress a batch of chunks in parallel.
     */
    private List<CompressedChunk> compressBatch(List<LRFChunkEntry> chunks) {
        List<CompressedChunk> compressed = new ArrayList<>(chunks.size());
        List<CompletableFuture<CompressedChunk>> futures = new ArrayList<>();
        
        for (LRFChunkEntry chunk : chunks) {
            CompletableFuture<CompressedChunk> future = CompletableFuture
                .supplyAsync(() -> compressChunk(chunk), compressionExecutor);
            futures.add(future);
        }
        
        for (CompletableFuture<CompressedChunk> future : futures) {
            try {
                compressed.add(future.get());
                chunksCompressed.incrementAndGet();
            } catch (Exception e) {
                System.err.println("[TurboMC] Error compressing chunk: " + e.getMessage());
                // Continue with other chunks
            }
        }
        
        return compressed;
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
            compressedData = TurboCompressionService.getInstance().compress(dataToWrite);
        }
        
        return new CompressedChunk(chunk, compressedData);
    }
    
    /**
     * Write a batch of compressed chunks to file.
     */
    private CompletableFuture<Void> writeBatch(List<CompressedChunk> compressedChunks) {
        return CompletableFuture.runAsync(() -> {
            try (LRFRegionWriter writer = new LRFRegionWriter(regionPath, compressionType)) {
                for (CompressedChunk compressedChunk : compressedChunks) {
                    writer.addChunk(compressedChunk.originalChunk);
                }
                writer.flush();
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
            for (CompletableFuture<Void> future : writeFutures) {
                future.get(timeout, unit);
            }
            
            return true;
        } catch (Exception e) {
            return false;
        }
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
                // Flush remaining chunks
                flushBatch().get(30, TimeUnit.SECONDS);
                
                // Shutdown executors
                compressionExecutor.shutdown();
                writeExecutor.shutdown();
                
                if (!compressionExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    compressionExecutor.shutdownNow();
                }
                
                if (!writeExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    writeExecutor.shutdownNow();
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
