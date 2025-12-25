package com.turbomc.storage.mmap;

import java.nio.MappedByteBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Synchronization barrier for MMap operations.
 * Prevents race conditions between writes (flush) and reads (prefetch).
 * 
 * @author TurboMC
 * @version 2.3.3
 */
public class FlushBarrier {
    
    private final ReentrantReadWriteLock globalLock = new ReentrantReadWriteLock();
    private final Map<Path, MappedByteBuffer> activeBuffers = new ConcurrentHashMap<>();
    private final boolean verbose;
    
    public FlushBarrier(boolean verbose) {
        this.verbose = verbose;
    }
    
    /**
     * Called before flushing writes to disk.
     * Blocks all concurrent reads until flush completes.
     */
    public void beforeFlush(Path regionPath) {
        globalLock.writeLock().lock();
        
        if (verbose) {
            System.out.println("[TurboMC][FlushBarrier] Write lock acquired for flush: " + regionPath);
        }
    }
    
    /**
     * Called after flushing writes to disk.
     * Releases write lock, allowing reads to proceed.
     * 
     * @param regionPath Path to the region file
     * @param buffer MappedByteBuffer to force to disk (nullable)
     */
    public void afterFlush(Path regionPath, MappedByteBuffer buffer) {
        try {
            // Force OS to flush buffer to disk
            if (buffer != null) {
                buffer.force();
                
                if (verbose) {
                    System.out.println("[TurboMC][FlushBarrier] Forced buffer to disk: " + regionPath);
                }
            }
        } finally {
            globalLock.writeLock().unlock();
            
            if (verbose) {
                System.out.println("[TurboMC][FlushBarrier] Write lock released: " + regionPath);
            }
        }
    }
    
    /**
     * Called before reading from MMap.
     * Blocks if concurrent flush is in progress.
     */
    public void beforeRead() {
        globalLock.readLock().lock();
        
        if (verbose) {
            System.out.println("[TurboMC][FlushBarrier] Read lock acquired");
        }
    }
    
    /**
     * Called after reading from MMap.
     * Releases read lock.
     * 
     * @param buffer MappedByteBuffer to potentially invalidate (nullable)
     */
    public void afterRead(MappedByteBuffer buffer) {
        try {
            // Optional: Force buffer to reload from disk on next access
            // This is only needed if we suspect OS-level caching issues
            // Commented out by default for performance
            
            // if (buffer != null && buffer instanceof sun.nio.ch.DirectBuffer) {
            //     try {
            //         ((sun.nio.ch.DirectBuffer) buffer).cleaner().clean();
            //     } catch (Exception ignored) {}
            // }
        } finally {
            globalLock.readLock().unlock();
            
            if (verbose) {
                System.out.println("[TurboMC][FlushBarrier] Read lock released");
            }
        }
    }
    
    /**
     * Register an active buffer for tracking.
     */
    public void registerBuffer(Path regionPath, MappedByteBuffer buffer) {
        activeBuffers.put(regionPath, buffer);
    }
    
    /**
     * Unregister a buffer when no longer needed.
     */
    public void unregisterBuffer(Path regionPath) {
        activeBuffers.remove(regionPath);
    }
    
    /**
     * Force all registered buffers to disk.
     * Used during shutdown.
     */
    public void forceAll() {
        beforeFlush(null);
        
        try {
            for (Map.Entry<Path, MappedByteBuffer> entry : activeBuffers.entrySet()) {
                try {
                    entry.getValue().force();
                    
                    if (verbose) {
                        System.out.println("[TurboMC][FlushBarrier] Forced shutdown flush: " + entry.getKey());
                    }
                } catch (Exception e) {
                    System.err.println("[TurboMC][FlushBarrier] Failed to force buffer: " + e.getMessage());
                }
            }
        } finally {
            afterFlush(null, null);
        }
    }
    
    /**
     * Get statistics about barrier usage.
     */
    public BarrierStats getStats() {
        return new BarrierStats(
            globalLock.getReadLockCount(),
            globalLock.isWriteLocked(),
            globalLock.getQueueLength(),
            activeBuffers.size()
        );
    }
    
    public record BarrierStats(int activeReads, boolean writeLocked, int queueLength, int bufferCount) {
        @Override
        public String toString() {
            return String.format("FlushBarrier{reads=%d, writeLocked=%s, queued=%d, buffers=%d}",
                activeReads, writeLocked, queueLength, bufferCount);
        }
    }
}
