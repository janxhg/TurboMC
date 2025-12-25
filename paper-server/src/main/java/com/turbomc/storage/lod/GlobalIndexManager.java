package com.turbomc.storage.lod;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the LOD 4 Global World Index (.twi).
 * Provides ultra-fast, world-scale visibility by storing 1 byte of data per chunk.
 * 
 * Format (8 bits):
 * [Generated: 1 bit] [Height: 4 bits (16-block steps)] [BiomeCategory: 3 bits]
 */
public class GlobalIndexManager {
    private static final GlobalIndexManager INSTANCE = new GlobalIndexManager();
    
    private final Map<String, Map<Long, Byte>> worldIndices = new ConcurrentHashMap<>();
    private final Map<String, Path> worldPaths = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> dirtyFlags = new ConcurrentHashMap<>();

    private GlobalIndexManager() {}

    public static GlobalIndexManager getInstance() {
        return INSTANCE;
    }

    public void initialize(String worldName, Path worldPath) {
        Path indexPath = worldPath.resolve("turbo_index.twi");
        worldPaths.put(worldName, indexPath);
        worldIndices.put(worldName, new ConcurrentHashMap<>());
        dirtyFlags.put(worldName, new AtomicBoolean(false));
        loadIndex(worldName);
    }

    /**
     * Get LOD 4 data for a chunk in a specific world.
     */
    public byte getChunkInfo(String worldName, int x, int z) {
        Map<Long, Byte> index = worldIndices.get(worldName);
        return index != null ? index.getOrDefault(getChunkKey(x, z), (byte) 0) : 0;
    }

    /**
     * Update LOD 4 data for a specific world.
     */
    public void updateChunkInfo(String worldName, int x, int z, byte data) {
        Map<Long, Byte> index = worldIndices.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>());
        Long key = getChunkKey(x, z);
        Byte old = index.put(key, data);
        if (old == null || old != data) {
            AtomicBoolean dirty = dirtyFlags.computeIfAbsent(worldName, k -> new AtomicBoolean(false));
            dirty.set(true);
        }
    }

    private void loadIndex(String worldName) {
        Path indexPath = worldPaths.get(worldName);
        if (indexPath == null || !indexPath.toFile().exists()) return;
        
        Map<Long, Byte> index = worldIndices.get(worldName);
        if (index == null) return;

        try (FileChannel fc = FileChannel.open(indexPath, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(12);
            fc.read(buffer);
            buffer.flip();
            
            if (buffer.getInt() != 0x54574900) return;
            int version = buffer.getInt();
            int count = buffer.getInt();
            
            ByteBuffer entryBuffer = ByteBuffer.allocate(9);
            for (int i = 0; i < count; i++) {
                entryBuffer.clear();
                if (fc.read(entryBuffer) != 9) break;
                entryBuffer.flip();
                index.put(getChunkKey(entryBuffer.getInt(), entryBuffer.getInt()), entryBuffer.get());
            }
            System.out.println("[TurboMC][LOD4] Loaded " + index.size() + " entries for world: " + worldName);
        } catch (IOException e) {
            System.err.println("[TurboMC][LOD4] Failed to load index for " + worldName + ": " + e.getMessage());
        }
    }

    public void saveAll() {
        for (String worldName : worldPaths.keySet()) {
            saveIndex(worldName);
        }
    }

    public void saveIndex(String worldName) {
        AtomicBoolean dirty = dirtyFlags.get(worldName);
        if (dirty == null || !dirty.get()) return;
        
        Path indexPath = worldPaths.get(worldName);
        Map<Long, Byte> index = worldIndices.get(worldName);
        if (indexPath == null || index == null) return;
        
        try (FileChannel fc = FileChannel.open(indexPath, 
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            
            ByteBuffer header = ByteBuffer.allocate(12);
            header.putInt(0x54574900);
            header.putInt(1);
            header.putInt(index.size());
            header.flip();
            fc.write(header);
            
            ByteBuffer entryBuffer = ByteBuffer.allocate(9 * 1024);
            for (Map.Entry<Long, Byte> entry : index.entrySet()) {
                if (entryBuffer.remaining() < 9) {
                    entryBuffer.flip();
                    fc.write(entryBuffer);
                    entryBuffer.clear();
                }
                long key = entry.getKey();
                entryBuffer.putInt((int)(key >> 32));
                entryBuffer.putInt((int)key);
                entryBuffer.put(entry.getValue());
            }
            entryBuffer.flip();
            fc.write(entryBuffer);
            
            dirty.set(false);
            System.out.println("[TurboMC][LOD4] Saved index for " + worldName + " (" + index.size() + " entries).");
        } catch (IOException e) {
            System.err.println("[TurboMC][LOD4] Failed to save index for " + worldName + ": " + e.getMessage());
        }
    }

    private long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
    
    /**
     * Utility to pack LOD 4 data.
     */
    public static byte pack(boolean generated, int height, int biomeCategory) {
        int g = generated ? 1 : 0;
        int h = Math.max(0, Math.min(15, height / 16));
        int b = Math.max(0, Math.min(7, biomeCategory));
        return (byte) ((g << 7) | (h << 3) | b);
    }
}
