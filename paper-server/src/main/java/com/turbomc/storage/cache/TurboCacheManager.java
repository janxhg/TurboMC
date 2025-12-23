package com.turbomc.storage.cache;

import com.turbomc.config.TurboConfig;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TurboCacheManager provides a thread-safe L1 RAM cache for hot chunks.
 * Uses LRU eviction based on memory weight.
 */
public class TurboCacheManager {
    private static volatile TurboCacheManager instance;
    private static final Object INIT_LOCK = new Object();

    private final Map<CacheKey, CacheEntry> cache;
    private final long maxSizeBytes;
    private final long ttlMillis; // Time-to-Live for cache entries
    private long currentSizeBytes; // Protected by lock
    private final Object lock = new Object();

    // Efficient key using record (Java 16+)
    private record CacheKey(Path path, int x, int z) {}
    
    // Cache entry with metadata
    private static class CacheEntry {
        final byte[] data;
        final long timestamp;
        
        CacheEntry(byte[] data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired(long ttl) {
            return ttl > 0 && (System.currentTimeMillis() - timestamp) > ttl;
        }
    }

    private TurboCacheManager() {
        // Read from config or default to 128MB (reduced for memory optimization)
        long maxSizeMB = 128;
        long ttlMinutes = 10; // Default 10 minute TTL
        
        if (TurboConfig.isInitialized()) {
             maxSizeMB = TurboConfig.get().getMaxMemoryUsage();
        }
        
        this.maxSizeBytes = maxSizeMB * 1024 * 1024;
        this.ttlMillis = ttlMinutes * 60 * 1000;
        this.currentSizeBytes = 0;
        
        // Standard HashMap - we'll handle eviction manually for better control
        this.cache = new LinkedHashMap<>(1024, 0.75f, true);
        
        System.out.println("[TurboMC] Cache Manager initialized. Max size: " + maxSizeMB + "MB, TTL: " + ttlMinutes + "m " + 
                           (TurboConfig.isInitialized() ? "(Configured)" : "(Default)"));
    }

    public static synchronized TurboCacheManager getInstance() {
        // PERF: Disabled based on benchmarks (2025-12-23)
        // With NVMe storage and high compression, cache adds 95% latency overhead without benefit.
        // Returning null bypasses all cache logic heavily.
        return null;
    }

    public byte[] get(Path regionPath, int x, int z) {
        CacheKey k = new CacheKey(regionPath, x, z);
        synchronized (lock) {
            CacheEntry entry = cache.get(k);
            if (entry == null) {
                return null;
            }
            
            // Check if expired
            if (entry.isExpired(ttlMillis)) {
                // Remove expired entry
                cache.remove(k);
                currentSizeBytes -= entry.data.length;
                return null;
            }
            
            return entry.data;
        }
    }

    public void put(Path regionPath, int x, int z, byte[] data) {
        if (data == null) return;
        CacheKey k = new CacheKey(regionPath, x, z);
        CacheEntry newEntry = new CacheEntry(data);
        
        synchronized (lock) {
            // Check if we need to evict before adding
            long requiredSpace = data.length;
            CacheEntry old = cache.get(k);
            if (old != null) {
                requiredSpace -= old.data.length; // We're replacing, so net change
            }
            
            // FIX: High watermark strategy - keep cache at 90% max
            // This prevents constant eviction when cache is nearly full
            long highWatermark = (long)(maxSizeBytes * 0.9);
            
            // If adding this would exceed high watermark, evict down to 80%
            if (currentSizeBytes + requiredSpace > highWatermark) {
                long targetSize = (long)(maxSizeBytes * 0.8);
                long toEvict = (currentSizeBytes + requiredSpace) - targetSize;
                
                // FIX: Batch eviction - evict multiple entries in one pass
                // This is MUCH faster than one-at-a-time (O(1) vs O(n²))
                long evicted = 0;
                var iterator = cache.entrySet().iterator();
                
                while (iterator.hasNext() && evicted < toEvict) {
                    var entry = iterator.next();
                    long entrySize = entry.getValue().data.length;
                    iterator.remove();
                    evicted += entrySize;
                    currentSizeBytes -= entrySize;
                }
                
                if (evicted > 0) {
                    System.out.println("[TurboMC][Cache] Batch evicted " + evicted / 1024 / 1024 + 
                        "MB to maintain performance (now at " + currentSizeBytes / 1024 / 1024 + "MB)");
                }
            }
            
            // If still too large after evicting everything, don't cache
            if (currentSizeBytes + requiredSpace > maxSizeBytes) {
                System.err.println("[TurboMC][Cache][WARN] Single chunk (" + requiredSpace / 1024 + 
                    "KB) exceeds available cache space, skipping");
                return;
            }
            
            // Add new entry with atomic size update (FIX #2: race condition)
            CacheEntry previous = cache.put(k, newEntry);
            if (previous != null) {
                currentSizeBytes -= previous.data.length;
            }
            currentSizeBytes += data.length;
        }
    }
    
    /**
     * Evict the oldest entry from cache (must be called within synchronized block).
     * DEPRECATED: Use batch eviction in put() instead for better performance.
     */
    @Deprecated
    private void evictOldest() {
        if (cache.isEmpty()) return;
        
        // Find oldest entry (first in LinkedHashMap with access-order=true)
        var iterator = cache.entrySet().iterator();
        if (iterator.hasNext()) {
            var eldest = iterator.next();
            currentSizeBytes -= eldest.getValue().data.length;
            iterator.remove();
        }
    }
    
    /**
     * Invalidate a specific chunk in cache (FIX #1: cache invalidation).
     */
    public void invalidate(Path regionPath, int x, int z) {
        CacheKey k = new CacheKey(regionPath, x, z);
        synchronized (lock) {
            CacheEntry removed = cache.remove(k);
            if (removed != null) {
                currentSizeBytes -= removed.data.length;
            }
        }
    }
    
    /**
     * Clear all cache entries for a specific region (FIX #1: region-level invalidation).
     */
    public void clearRegion(Path regionPath) {
        synchronized (lock) {
            cache.entrySet().removeIf(entry -> {
                if (entry.getKey().path.equals(regionPath)) {
                    currentSizeBytes -= entry.getValue().data.length;
                    return true;
                }
                return false;
            });
        }
    }
    
    /**
     * Clear all expired entries (should be called periodically).
     */
    public int cleanupExpired() {
        synchronized (lock) {
            int removed = 0;
            var iterator = cache.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getValue().isExpired(ttlMillis)) {
                    currentSizeBytes -= entry.getValue().data.length;
                    iterator.remove();
                    removed++;
                }
            }
            return removed;
        }
    }
    
    /**
     * Get cache statistics.
     */
    public CacheStats getStats() {
        synchronized (lock) {
            return new CacheStats(
                cache.size(),
                currentSizeBytes,
                maxSizeBytes,
                (double) currentSizeBytes / maxSizeBytes * 100
            );
        }
    }
    
    public record CacheStats(int entries, long currentBytes, long maxBytes, double usagePercent) {
        @Override
        public String toString() {
            return String.format("Cache{entries=%d, usage=%.1fMB/%.1fMB (%.1f%%)}",
                entries,
                currentBytes / 1024.0 / 1024.0,
                maxBytes / 1024.0 / 1024.0,
                usagePercent);
        }
    }
}
