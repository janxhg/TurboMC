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

    private final Map<CacheKey, byte[]> cache;
    private final long maxSizeBytes;
    private long currentSizeBytes;
    private final Object lock = new Object();

    // Efficient key using record (Java 16+)
    private record CacheKey(Path path, int x, int z) {}

    private TurboCacheManager() {
        // Read from config or default to 256MB
        long maxSizeMB = 256;
        if (TurboConfig.isInitialized()) {
             maxSizeMB = TurboConfig.get().getMaxMemoryUsage();
        }
        
        this.maxSizeBytes = maxSizeMB * 1024 * 1024;
        this.currentSizeBytes = 0;
        
        // LRU Map (access-order = true)
        this.cache = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, byte[]> eldest) {
                 if (currentSizeBytes > maxSizeBytes) {
                     // Subtract size of EVICTED item
                     if (eldest.getValue() != null) {
                        currentSizeBytes -= eldest.getValue().length;
                     }
                     return true;
                 }
                 return false;
            }
        };
        
        System.out.println("[TurboMC] Cache Manager initialized. Max size: " + maxSizeMB + "MB " + 
                           (TurboConfig.isInitialized() ? "(Configured)" : "(Default)"));
    }

    public static TurboCacheManager getInstance() {
        if (instance == null) {
            synchronized (INIT_LOCK) {
                if (instance == null) {
                    instance = new TurboCacheManager();
                }
            }
        }
        return instance;
    }

    public byte[] get(Path regionPath, int x, int z) {
        CacheKey k = new CacheKey(regionPath, x, z);
        synchronized (lock) {
            return cache.get(k);
        }
    }

    public void put(Path regionPath, int x, int z, byte[] data) {
        if (data == null) return;
        CacheKey k = new CacheKey(regionPath, x, z);
        
        synchronized (lock) {
            // If replacing, subtract old size
            byte[] old = cache.put(k, data);
            
            if (old != null) {
                currentSizeBytes -= old.length;
            }
            
            currentSizeBytes += data.length;
        }
    }
}
