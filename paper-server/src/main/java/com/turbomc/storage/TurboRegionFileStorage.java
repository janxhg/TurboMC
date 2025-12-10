package com.turbomc.storage;

import com.turbomc.config.TurboConfig;
import com.turbomc.compression.TurboCompressionService;
import com.turbomc.storage.LRFConstants;
import com.turbomc.storage.LRFChunkEntry;
import com.turbomc.storage.TurboStorageManager;
import com.turbomc.storage.ConversionMode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * TurboMC-enhanced region storage wrapper that integrates with the advanced storage system.
 * This wrapper provides seamless integration between Paper's chunk storage and TurboMC's
 * optimized batch loading, memory-mapped I/O, and integrity validation.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class TurboRegionFileStorage {
    
    private final RegionFileStorage delegate;
    private final TurboStorageManager storageManager;
    private final TurboConfig config;
    private final boolean verbose;
    private final Path regionFolder;
    private final boolean useTurboFeatures;
    
    public TurboRegionFileStorage(RegionStorageInfo info, Path folder, boolean sync) {
        // Use reflection to access protected constructor
        try {
            java.lang.reflect.Constructor<RegionFileStorage> constructor = RegionFileStorage.class.getDeclaredConstructor(
                RegionStorageInfo.class, Path.class, boolean.class);
            constructor.setAccessible(true);
            this.delegate = constructor.newInstance(info, folder, sync);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create RegionFileStorage delegate", e);
        }
        this.regionFolder = folder;
        this.config = TurboConfig.getInstance();
        this.verbose = config.getBoolean("storage.verbose", false);
        this.storageManager = TurboStorageManager.getInstance();
        
        // Check if TurboMC features are enabled for this world
        this.useTurboFeatures = isTurboEnabled();
        
        if (useTurboFeatures) {
            System.out.println("[TurboMC][RegionStorage] Turbo features enabled for: " + folder.getFileName());
        }
    }
    
    /**
     * Read a chunk with TurboMC optimizations.
     */
    @Nullable
    public CompoundTag read(ChunkPos pos) throws IOException {
        if (!useTurboFeatures) {
            return delegate.read(pos);
        }
        
        try {
            // Try to get region file path for this chunk
            Path regionPath = getRegionPath(pos);
            if (regionPath == null) {
                return delegate.read(pos);
            }
            
            // Check if this is an LRF file (TurboMC format)
            if (isLRFFile(regionPath)) {
                return readFromLRF(regionPath, pos);
            }
            
            // Fall back to vanilla MCA with optimization
            return readFromMCAOptimized(pos);
            
        } catch (Exception e) {
            System.err.println("[TurboMC][RegionStorage] Error in optimized read, falling back to vanilla: " + e.getMessage());
            return delegate.read(pos);
        }
    }
    
    /**
     * Write a chunk with TurboMC optimizations.
     */
    public void write(ChunkPos pos, CompoundTag nbt) throws IOException {
        if (!useTurboFeatures) {
            delegate.write(pos, nbt);
            return;
        }
        
        try {
            // Try to get region file path for this chunk
            Path regionPath = getRegionPath(pos);
            if (regionPath == null) {
                delegate.write(pos, nbt);
                return;
            }
            
            // Check if this should be saved as LRF (TurboMC format)
            if (shouldSaveAsLRF(regionPath)) {
                writeToLRF(regionPath, pos, nbt);
                return;
            }
            
            // Fall back to vanilla MCA with optimization
            writeToMCAOptimized(pos, nbt);
            
        } catch (Exception e) {
            System.err.println("[TurboMC][RegionStorage] Error in optimized write, falling back to vanilla: " + e.getMessage());
            delegate.write(pos, nbt);
        }
    }
    
    /**
     * Read chunk from LRF format with timeout and error handling.
     */
    @Nullable
    private CompoundTag readFromLRF(Path regionPath, ChunkPos pos) {
        try {
            CompletableFuture<LRFChunkEntry> future = storageManager.loadChunk(
                regionPath, pos.x, pos.z);
            
            // Wait for completion with timeout
            LRFChunkEntry chunk = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            
            if (chunk == null) {
                return null;
            }
            
            // Parse NBT from chunk data
            byte[] data = chunk.getData();
            if (data.length == 0) {
                return null;
            }
            
            // Remove timestamp if present (last 8 bytes)
            if (data.length > 8) {
                byte[] nbtData = new byte[data.length - 8];
                System.arraycopy(data, 0, nbtData, 0, nbtData.length);
                data = nbtData;
            }
            
            return NbtIo.read(new DataInputStream(new java.io.ByteArrayInputStream(data)), 
                           NbtAccounter.unlimitedHeap());
            
        } catch (java.util.concurrent.TimeoutException e) {
            System.err.println("[TurboMC][RegionStorage] Timeout reading chunk " + pos + " from LRF");
            return null;
        } catch (Exception e) {
            System.err.println("[TurboMC][RegionStorage] Failed to read from LRF: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Write chunk to LRF format with performance tracking.
     */
    private void writeToLRF(Path regionPath, ChunkPos pos, CompoundTag nbt) throws IOException {
        long startTime = System.nanoTime();
        
        // Serialize NBT to bytes
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.write(nbt, new DataOutputStream(baos));
        byte[] nbtData = baos.toByteArray();
        
        // Add timestamp (current time in milliseconds)
        ByteBuffer dataWithTimestamp = ByteBuffer.allocate(nbtData.length + 8);
        dataWithTimestamp.put(nbtData);
        dataWithTimestamp.putLong(System.currentTimeMillis());
        dataWithTimestamp.flip();
        
        byte[] dataToWrite = new byte[dataWithTimestamp.remaining()];
        dataWithTimestamp.get(dataToWrite);
        
        // Create chunk entry
        LRFChunkEntry chunk = new LRFChunkEntry(pos.x, pos.z, dataToWrite);
        
        // Save chunk asynchronously
        CompletableFuture<Void> future = storageManager.saveChunk(
            regionPath, pos.x, pos.z, dataToWrite);
        
        try {
            // Wait for completion with timeout
            future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            
            long elapsed = System.nanoTime() - startTime;
            if (verbose && elapsed > 1_000_000) { // Log if > 1ms
                System.out.println("[TurboMC][RegionStorage] Wrote chunk " + pos + 
                                 " to LRF in " + elapsed / 1_000_000 + "ms");
            }
            
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("Timeout writing chunk " + pos + " to LRF", e);
        } catch (Exception e) {
            throw new IOException("Failed to write chunk " + pos + " to LRF", e);
        }
    }
    
    /**
     * Optimized MCA read with caching.
     */
    @Nullable
    private CompoundTag readFromMCAOptimized(ChunkPos pos) throws IOException {
        // Check if MCA optimization is enabled
        if (!config.getBoolean("storage.mca.optimization.enabled", false)) {
            return delegate.read(pos);
        }
        
        // Use vanilla implementation for now
        // TODO: Implement MCA optimization with batch loading
        return delegate.read(pos);
    }
    
    /**
     * Optimized MCA write with batching.
     */
    private void writeToMCAOptimized(ChunkPos pos, CompoundTag nbt) throws IOException {
        // Check if MCA optimization is enabled
        if (!config.getBoolean("storage.mca.optimization.enabled", false)) {
            delegate.write(pos, nbt);
            return;
        }
        
        // Get region path for this chunk
        Path regionPath = getRegionPath(pos);
        if (regionPath != null && isLRFFile(regionPath)) {
            writeToLRF(regionPath, pos, nbt);
            return;
        }
        
        // Fall back to vanilla MCA with optimization
        delegate.write(pos, nbt);
    }
    
    /**
     * Get the region file path for a chunk position.
     */
    @Nullable
    private Path getRegionPath(ChunkPos pos) {
        try {
            RegionFile regionFile = delegate.getRegionFile(pos);
            if (regionFile != null) {
                // Use reflection to get regionPath since it's not directly accessible
                try {
                    java.lang.reflect.Field regionPathField = RegionFile.class.getDeclaredField("regionPath");
                    regionPathField.setAccessible(true);
                    return (Path) regionPathField.get(regionFile);
                } catch (Exception e) {
                    // Fallback: construct path from region coordinates
                    int regionX = pos.x >> 5;
                    int regionZ = pos.z >> 5;
                    return regionFolder.resolve(String.format("r.%d.%d.mca", regionX, regionZ));
                }
            }
        } catch (Exception e) {
            if (verbose) {
                System.err.println("[TurboMC][RegionStorage] Error getting region path: " + e.getMessage());
            }
        }
        return null;
    }
    
    /**
     * Check if a file is in LRF format.
     */
    private boolean isLRFFile(Path filePath) {
        return filePath.toString().endsWith(".lrf");
    }
    
    /**
     * Check if chunks should be saved as LRF format.
     */
    private boolean shouldSaveAsLRF(Path regionPath) {
        if (!TurboConfig.isInitialized()) {
            return false;
        }
        
        TurboConfig config = TurboConfig.getInstance();
        String conversionMode = config.getString("storage.conversion-mode", "manual");
        
        // In FULL_LRF mode, always create LRF files directly (no MCA conversion)
        if (ConversionMode.FULL_LRF.equals(ConversionMode.fromString(conversionMode))) {
            return true;
        }
        
        String storageFormat = config.getString("storage.format", "auto");
        
        if (storageFormat.equals("lrf")) {
            return true;
        } else if (storageFormat.equals("mca")) {
            return false;
        } else { // auto
            // Use LRF if region file already exists in LRF format
            return isLRFFile(regionPath) || config.getBoolean("storage.auto-convert", true);
        }
    }
    
    /**
     * Check if TurboMC features are enabled.
     */
    private boolean isTurboEnabled() {
        if (!TurboConfig.isInitialized()) {
            return false;
        }
        
        TurboConfig config = TurboConfig.getInstance();
        
        // Always enable in FULL_LRF mode
        String conversionMode = config.getString("storage.conversion-mode", "manual");
        if (ConversionMode.FULL_LRF.equals(ConversionMode.fromString(conversionMode))) {
            return true;
        }
        
        // Otherwise check for other Turbo features
        return config.getBoolean("storage.batch.enabled", true) ||
               config.getBoolean("storage.mmap.enabled", true) ||
               config.getBoolean("storage.integrity.enabled", true);
    }
    
    /**
     * Validate region file integrity.
     */
    public boolean validateRegion(Path regionPath) {
        if (!isLRFFile(regionPath)) {
            return true; // Skip MCA validation for now
        }
        
        // TODO: Implement validation when method is available
        if (verbose) {
            System.out.println("[TurboMC][RegionStorage] Validation not yet implemented for " + regionPath);
        }
        return true;
    }
    
    /**
     * Enhanced storage statistics.
     */
    public static class StorageStats {
        public final Object managerStats;
        public final int regionCount;
        public final double cacheHitRate;
        
        public StorageStats(Object managerStats, int regionCount, double cacheHitRate) {
            this.managerStats = managerStats;
            this.regionCount = regionCount;
            this.cacheHitRate = cacheHitRate;
        }
        
        @Override
        public String toString() {
            return String.format("StorageStats{regions=%d, cacheHitRate=%.1f%%, manager=%s}",
                    regionCount, cacheHitRate * 100,
                    managerStats != null ? managerStats.toString() : "disabled");
        }
    }
    
    /**
     * Get comprehensive statistics.
     */
    public StorageStats getStorageStats() {
        if (storageManager != null) {
            try {
                return new StorageStats(
                    storageManager.getStats(),
                    getRegionFileCount(),
                    getCacheHitRate()
                );
            } catch (Exception e) {
                return new StorageStats(null, 0, 0.0);
            }
        }
        return new StorageStats(null, 0, 0.0);
    }
    
    private int getRegionFileCount() {
        // TODO: Implement region file counting
        return 0;
    }
    
    private double getCacheHitRate() {
        // TODO: Implement cache hit rate calculation
        return 0.0;
    }
    
    /**
     * Get storage statistics for this region storage.
     */
    public TurboStorageManager.StorageManagerStats getTurboStats() {
        if (storageManager != null) {
            return storageManager.getStats();
        }
        return null;
    }
    
    /**
     * Add missing methods for compatibility.
     */
    public void flush() throws IOException {
        // No-op for now - delegate handles flushing
        if (verbose) {
            System.out.println("[TurboMC][RegionStorage] Flush called");
        }
    }
    
    public void close() throws IOException {
        if (storageManager != null) {
            try {
                storageManager.close();
                if (verbose) {
                    System.out.println("[TurboMC][RegionStorage] Closed TurboMC resources for world");
                }
            } catch (Exception e) {
                System.err.println("[TurboMC][RegionStorage] Error closing TurboMC resources: " + e.getMessage());
            }
        }
    }
    
    /**
     * Add scanRegion method for compatibility.
     */
    public void scanRegion(int regionX, int regionZ, java.util.function.BiPredicate<Integer, Integer> chunkVisitor) {
        // For now, delegate to parent implementation
        // This could be enhanced with TurboMC-specific optimizations
        try {
            // Iterate through all chunks in the region (32x32 = 1024 chunks)
            for (int chunkX = regionX * 32; chunkX < regionX * 32 + 32; chunkX++) {
                for (int chunkZ = regionZ * 32; chunkZ < regionZ * 32 + 32; chunkZ++) {
                    if (chunkVisitor.test(chunkX, chunkZ)) {
                        // Chunk accepted by visitor
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[TurboMC][RegionStorage] Error scanning region: " + e.getMessage());
        }
    }
    
    /**
     * Add validateRegion overload for compatibility.
     */
    public CompletableFuture<java.util.List<ChunkIntegrityValidator.IntegrityReport>> validateRegion(int regionX, int regionZ) {
        if (!useTurboFeatures) {
            return CompletableFuture.completedFuture(new java.util.ArrayList<>());
        }
        
        String regionFileName = String.format("r.%d.%d.lrf", regionX, regionZ);
        Path regionPath = regionFolder.resolve(regionFileName);
        
        if (!java.nio.file.Files.exists(regionPath)) {
            return CompletableFuture.completedFuture(new java.util.ArrayList<>());
        }
        
        // TODO: Implement actual validation when validator is available
        return CompletableFuture.completedFuture(new java.util.ArrayList<>());
    }
}
