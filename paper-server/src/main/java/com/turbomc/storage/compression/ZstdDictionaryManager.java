package com.turbomc.storage.compression;

import com.turbomc.config.TurboConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for ZSTD dictionaries.
 * v2.1 Experimental: Allows using shared dictionaries to improve chunk compression.
 */
public class ZstdDictionaryManager {
    
    private static final ZstdDictionaryManager INSTANCE = new ZstdDictionaryManager();
    private final ConcurrentHashMap<String, byte[]> dictCache = new ConcurrentHashMap<>();
    private final Path dictFolder;

    private ZstdDictionaryManager() {
        this.dictFolder = Path.of("turbo_data", "dictionaries");
        try {
            Files.createDirectories(dictFolder);
        } catch (IOException ignored) {}
    }

    public static ZstdDictionaryManager getInstance() {
        return INSTANCE;
    }

    /**
     * Get a dictionary for a specific region or use the global default.
     */
    public byte[] getDictionary(String regionId) {
        // Try region-specific dict first
        byte[] dict = dictCache.get(regionId);
        if (dict != null) return dict;

        // Try global default
        return dictCache.get("global_default");
    }

    /**
     * Load a dictionary from disk.
     */
    public void loadDictionary(String id, Path path) throws IOException {
        if (Files.exists(path)) {
            byte[] bytes = Files.readAllBytes(path);
            dictCache.put(id, bytes);
            System.out.println("[TurboMC] Loaded ZSTD dictionary: " + id + " (" + (bytes.length / 1024) + " KB)");
        }
    }
    
    public boolean hasDictionary(String id) {
        return dictCache.containsKey(id);
    }
}
