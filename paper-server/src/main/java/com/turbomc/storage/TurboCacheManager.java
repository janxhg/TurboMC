package com.turbomc.storage;

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

    private final Map<String, byte[]> cache;
    private final long maxSizeBytes;
    private long currentSizeBytes;
    private final Object lock = new Object();

    private TurboCacheManager() {
        // Default to 256MB if not configured
        long maxSizeMB = 256;
        try {
            if (TurboConfig.isInitialized()) {
                // Accessing toml directly via reflection or if publicly exposed?
                // TurboConfig doesn't expose underlying Toml easily unless we added getter.
                // Assuming defaults for now or reading safe method.
                // We'll stick to a safe default and TODO: bind to config.
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        this.maxSizeBytes = maxSizeMB * 1024 * 1024;
        this.currentSizeBytes = 0;
        
        // LRU Map (access-order = true)
        this.cache = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
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
        
        System.out.println("[TurboMC] Cache Manager initialized. Max size: " + maxSizeMB + "MB");
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

    private String key(Path regionPath, int x, int z) {
        // Optimized key generation? 
        // Still creating strings, but safer than collision-prone hashes.
        return regionPath.getFileName().toString() + ":" + x + ":" + z;
    }

    public byte[] get(Path regionPath, int x, int z) {
        String k = key(regionPath, x, z);
        synchronized (lock) {
            return cache.get(k);
        }
    }

    public void put(Path regionPath, int x, int z, byte[] data) {
        if (data == null) return;
        String k = key(regionPath, x, z);
        
        synchronized (lock) {
            // If replacing, subtract old size
            byte[] old = cache.put(k, data);
            
            if (old != null) {
                currentSizeBytes -= old.length;
            }
            
            currentSizeBytes += data.length;
            
            // Trigger eviction check manually if needed? 
            // LinkedHashMap triggers removeEldestEntry inside put().
            // But removeEldestEntry removes the *eldest*, which updates the map.
            // Does LinkedHashMap handle the modification during put? Yes.
            // But we need to update currentSizeBytes when eviction happens!
            // The anonymous class above can't easily see 'currentSizeBytes' if it's outside scope or we need a callback.
        }
    }
    
    // We need to handle size update on eviction.
    // Inner class solution:
    private class LRUMap extends LinkedHashMap<String, byte[]> {
        public LRUMap() {
             super(1024, 0.75f, true);
        }
        
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            if (currentSizeBytes > maxSizeBytes) {
                currentSizeBytes -= eldest.getValue().length;
                return true;
            }
            return false;
        }
    }
}
