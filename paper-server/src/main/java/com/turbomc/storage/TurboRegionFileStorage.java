package com.turbomc.storage;

import com.turbomc.config.TurboConfig;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
     * Read chunk from LRF format using TurboMC storage manager.
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
            
        } catch (Exception e) {
            System.err.println("[TurboMC][RegionStorage] Failed to read from LRF: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Write chunk to LRF format using TurboMC storage manager.
     */
    private void writeToLRF(Path regionPath, ChunkPos pos, CompoundTag nbt) throws IOException {
        // Convert NBT to bytes
        java.io.ByteArrayOutputStream byteStream = new java.io.ByteArrayOutputStream();
        DataOutputStream dataStream = new DataOutputStream(byteStream);
        
        NbtIo.write(nbt, dataStream);
        byte[] nbtData = byteStream.toByteArray();
        
        // Save using TurboMC storage manager
        try {
            CompletableFuture<Void> future = storageManager.saveChunk(
                regionPath, pos.x, pos.z, nbtData);
            
            // Wait for completion with timeout
            future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            
        } catch (Exception e) {
            throw new IOException("Failed to write to LRF: " + e.getMessage(), e);
        }
    }
    
    /**
     * Optimized MCA read with caching.
     */
    @Nullable
    private CompoundTag readFromMCAOptimized(ChunkPos pos) throws IOException {
        // For now, use vanilla implementation
        // TODO: Implement MCA optimization with batch loading
        return delegate.read(pos);
    }
    
    /**
     * Optimized MCA write with batching.
     */
    private void writeToMCAOptimized(ChunkPos pos, CompoundTag nbt) throws IOException {
        // For now, use vanilla implementation  
        // TODO: Implement MCA optimization with batch saving
        delegate.write(pos, nbt);
    }
    
    /**
     * Get the region file path for a chunk position.
     */
    @Nullable
    private Path getRegionPath(ChunkPos pos) {
        int regionX = pos.getRegionX();
        int regionZ = pos.getRegionZ();
        
        String regionFileName = String.format("r.%d.%d.mca", regionX, regionZ);
        Path lrfPath = regionFolder.resolve(regionFileName.replace(".mca", ".lrf"));
        Path mcaPath = regionFolder.resolve(regionFileName);
        
        // Prioridad: LRF > MCA, pero si ambos existen, usar solo LRF
        if (java.nio.file.Files.exists(lrfPath)) {
            return lrfPath;
        }
        
        return mcaPath;
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
        return config.getBoolean("storage.batch.enabled", true) ||
               config.getBoolean("storage.mmap.enabled", true) ||
               config.getBoolean("storage.integrity.enabled", true);
    }
    
    /**
     * Get storage statistics for this region storage.
     */
    public TurboStorageManager.StorageManagerStats getTurboStats() {
        return storageManager.getStats();
    }
    
    /**
     * Close all TurboMC resources for this world.
     * Call this when the world is unloaded.
     */
    public void close() throws IOException {
        try {
            // Close all TurboMC storage components for this world
            if (useTurboFeatures) {
                System.out.println("[TurboMC][RegionStorage] Closing TurboMC resources for: " + regionFolder.getFileName());
                
                // Close all region files in this folder
                try (java.util.stream.Stream<Path> stream = java.nio.file.Files.list(regionFolder)) {
                    stream.filter(path -> path.toString().endsWith(".lrf") || path.toString().endsWith(".mca"))
                          .forEach(storageManager::closeRegion);
                } catch (Exception e) {
                    System.err.println("[TurboMC][RegionStorage] Error closing region files: " + e.getMessage());
                }
            }
        } finally {
            // Always close the parent storage
            delegate.close();
        }
    }
    
    /**
     * Force flush any pending operations.
     * Call this during world save.
     */
    public void flush() throws IOException {
        if (useTurboFeatures) {
            // TODO: Implement flush for batch savers
            System.out.println("[TurboMC][RegionStorage] Flushing pending operations...");
        }
    }
    
    /**
     * Validate integrity of all chunks in a region.
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
        
        try {
            LRFRegionReader reader = new LRFRegionReader(regionPath);
            // Create validator directly since getIntegrityValidator is private
            ChunkIntegrityValidator validator = new ChunkIntegrityValidator(regionPath);
            
            if (validator != null) {
                CompletableFuture<java.util.List<ChunkIntegrityValidator.IntegrityReport>> future = 
                    validator.validateRegion(reader);
                reader.close();
                return future;
            }
            
            reader.close();
        } catch (IOException e) {
            System.err.println("[TurboMC][RegionStorage] Failed to validate region: " + e.getMessage());
        }
        
        return CompletableFuture.completedFuture(new java.util.ArrayList<>());
    }
    
        
    /**
     * Scan all chunks in a region.
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
}
