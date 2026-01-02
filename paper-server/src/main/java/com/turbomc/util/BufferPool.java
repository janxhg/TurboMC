package com.turbomc.util;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance buffer pooling utility to reduce GC pressure.
 * Manages buckets of byte arrays for common sizes used in chunk loading/compression.
 */
public class BufferPool {
    
    private static final BufferPool INSTANCE = new BufferPool();
    
    // Buckets for common sizes: 1KB, 4KB, 16KB, 64KB, 256KB, 1MB
    private final Queue<byte[]>[] buckets;
    private final int[] sizes = {1024, 4096, 16384, 65536, 262144, 1048576};
    
    // Stats
    private final AtomicLong acquiredCount = new AtomicLong(0);
    private final AtomicLong releasedCount = new AtomicLong(0);
    private final AtomicLong createdCount = new AtomicLong(0);
    
    @SuppressWarnings("unchecked")
    private BufferPool() {
        buckets = new ConcurrentLinkedQueue[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            buckets[i] = new ConcurrentLinkedQueue<>();
        }
    }
    
    public static BufferPool getInstance() {
        return INSTANCE;
    }
    
    /**
     * Acquire a buffer of at least the specified size.
     */
    public byte[] acquire(int minSize) {
        acquiredCount.incrementAndGet();
        
        int bucketIndex = getBucketIndex(minSize);
        if (bucketIndex != -1) {
            byte[] buffer = buckets[bucketIndex].poll();
            if (buffer != null) {
                return buffer;
            }
        }
        
        // No buffer available in bucket or size too large, create new
        createdCount.incrementAndGet();
        
        // If it fits a bucket, create at bucket size, otherwise exact size
        int actualSize = (bucketIndex != -1) ? sizes[bucketIndex] : minSize;
        return new byte[actualSize];
    }
    
    /**
     * Release a buffer back to the pool.
     */
    public void release(byte[] buffer) {
        if (buffer == null) return;
        
        releasedCount.incrementAndGet();
        
        int bucketIndex = getExactBucketIndex(buffer.length);
        if (bucketIndex != -1) {
            // Keep buckets reasonably small to avoid memory leaks
            if (buckets[bucketIndex].size() < 128) {
                buckets[bucketIndex].offer(buffer);
            }
        }
    }
    
    private int getBucketIndex(int size) {
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] >= size) return i;
        }
        return -1;
    }
    
    private int getExactBucketIndex(int size) {
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] == size) return i;
        }
        return -1;
    }
    
    public String getStats() {
        StringBuilder sb = new StringBuilder("BufferPoolStats: ");
        sb.append("Acquired=").append(acquiredCount.get())
          .append(", Released=").append(releasedCount.get())
          .append(", Created=").append(createdCount.get())
          .append(" [");
        for (int i = 0; i < sizes.length; i++) {
            sb.append(sizes[i]/1024).append("K:").append(buckets[i].size()).append(" ");
        }
        sb.append("]");
        return sb.toString();
    }
}
