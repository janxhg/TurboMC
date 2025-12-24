package com.turbomc.storage.lrf;

import com.turbomc.storage.optimization.TurboStorageManager;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

public class LRFRegionFileAdapter extends RegionFile {

    private final Path filePath;
    private final FileChannel channel;
    private final LRFHeader header;
    private final Object fileLock = new Object();

    public LRFRegionFileAdapter(RegionStorageInfo info, Path path) throws IOException {
        super(info, path);
        this.filePath = path;

        boolean exists = Files.exists(path);
        
        this.channel = FileChannel.open(path, 
            StandardOpenOption.CREATE, 
            StandardOpenOption.READ, 
            StandardOpenOption.WRITE);

        if (!exists || channel.size() < LRFConstants.HEADER_SIZE) {
            // New file or invalid, initialize header
            this.header = new LRFHeader();
            // Write initial empty header
            ByteBuffer buf = ByteBuffer.allocate(LRFConstants.HEADER_SIZE);
            this.header.write(buf);
            buf.flip();
            channel.write(buf, 0);
        } else {
            // Read existing header
            ByteBuffer buf = ByteBuffer.allocate(LRFConstants.HEADER_SIZE);
            channel.read(buf, 0);
            buf.flip();
            this.header = LRFHeader.read(buf);
        }
    }

    @Override
    public DataInputStream getChunkDataInputStream(ChunkPos chunkPos) throws IOException {
        // Safe for concurrent access:
        // - header.hasChunk / getChunkOffset are consistent or synchronized internally
        // - FileChannel.read(dst, position) is thread-safe
        
        int chunkX = chunkPos.x;
        int chunkZ = chunkPos.z;
        
        // Use Global TurboStorageManager for EVERYTHING.
        // This ensures LRF files benefit from mmap, prefetching, and shared buffers.
        TurboStorageManager manager = TurboStorageManager.getInstance();
        try {
            CompletableFuture<LRFChunkEntry> future = manager.loadChunk(filePath, chunkX, chunkZ);
            LRFChunkEntry chunk = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            
            if (chunk == null || chunk.getData().length == 0) {
                return null;
            }
            
            byte[] data = chunk.getData();
            
            // Check for packed binary format
            if (data.length > 4 && data[0] == 'T' && data[1] == 'N' && data[2] == 'B' && data[3] == 'T') {
                try {
                    // Transcode back to standard NBT bytes for vanilla compatibility
                    net.minecraft.nbt.CompoundTag tag = com.turbomc.nbt.NBTConverter.fromPackedBinary(
                        com.turbomc.nbt.PackedBinaryNBT.fromBytes(data)
                    );
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    net.minecraft.nbt.NbtIo.write(tag, new DataOutputStream(bos));
                    return new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
                } catch (Exception e) {
                    System.err.println("[TurboMC][LRFAdapter] TNBT transcoding failed for " + chunkPos + ": " + e.getMessage());
                    // Fallthrough to raw data if transcoding fails
                }
            }
            
            return new DataInputStream(new ByteArrayInputStream(data));
        } catch (Exception e) {
            System.err.println("[TurboMC][LRFAdapter] Turbo load failed for " + chunkPos + ": " + e.getMessage());
            // Fallback to legacy sync read if manager fails (should not happen)
            return super.getChunkDataInputStream(chunkPos);
        }
    }

    @Override
    public DataOutputStream getChunkDataOutputStream(ChunkPos chunkPos) throws IOException {
        // Return a stream that buffers data and writes on close
        return new DataOutputStream(new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                super.close();
                writeChunk(chunkPos, this.toByteArray());
            }
        });
    }

    @Override
    protected synchronized void write(ChunkPos chunkPos, ByteBuffer chunkData) throws IOException {
        // This is called by Moonrise/Paper optimized IO.
        // chunkData includes: LENGTH (4) + TYPE (1) + DATA (N)
        
        // 1. Read Header
        int length = chunkData.getInt();
        byte type = chunkData.get();
        // Remaining is payload (compressed data)
        
        // 2. Extract Payload
        byte[] payload = new byte[chunkData.remaining()];
        chunkData.get(payload);
        
        // 3. Decompress (Transcode) to Raw NBT
        // LRF wants to manage compression itself (LZ4/ZSTD/Global) with consistent headers.
        byte[] nbtData;
        
        try {
            net.minecraft.world.level.chunk.storage.RegionFileVersion version = 
                net.minecraft.world.level.chunk.storage.RegionFileVersion.fromId(type);
                
            if (version == null) {
                // If unknown, it might be uncompressed if type=3 but fromId handles 1,2,3,4
                // Fallback check?
                throw new IOException("Unknown compression type in RegionFile.write: " + type);
            }
            
            try (InputStream in = version.wrap(new ByteArrayInputStream(payload))) {
                nbtData = in.readAllBytes();
            }
        } catch (Exception e) {
             throw new IOException("Failed to decompress chunk data (Type: " + type + ") for transcoding to LRF", e);
        }

        // 4. Write to LRF (Re-compresses)
        writeChunk(chunkPos, nbtData);
    }
    
    private void writeChunk(ChunkPos chunkPos, byte[] nbtData) throws IOException {
        // Use Global TurboStorageManager for EVERYTHING.
        // This ensures writes are batched, compressed correctly, and integrity-validated.
        TurboStorageManager manager = TurboStorageManager.getInstance();
        
        try {
            // Marshall to PackedBinary (v2.0 standard)
            byte[] dataToWrite = com.turbomc.nbt.NBTConverter.toPackedBinary(
                NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbtData)), NbtAccounter.unlimitedHeap())
            ).toBytes();
            
            // Hand off to manager (non-blocking if batching is enabled)
            manager.saveChunk(filePath, chunkPos.x, chunkPos.z, dataToWrite);
            
            // Update local header for "exists" checks (approximate, since write is async)
            // LRFConstants.getChunkIndex(chunkX, chunkZ)
            header.setChunkData(chunkPos.x & 31, chunkPos.z & 31, -1, dataToWrite.length);
            
        } catch (Exception e) {
            System.err.println("[TurboMC][LRFAdapter] Turbo write handoff failed for " + chunkPos + ": " + e.getMessage());
            throw new IOException("Failed to write chunk via TurboStorageManager", e);
        }
    }

    @Override
    public void flush() throws IOException {
        channel.force(true);
    }
    
    @Override
    public boolean doesChunkExist(ChunkPos chunkPos) {
        return header.hasChunk(chunkPos.x & 31, chunkPos.z & 31);
    }
    
    /**
     * Get chunk entry for inspection purposes.
     */
    public LRFChunkEntry getChunk(int chunkX, int chunkZ) throws IOException {
        int localX = chunkX & 31;
        int localZ = chunkZ & 31;
        
        if (!header.hasChunk(localX, localZ)) {
            return null;
        }
        
        long offset = header.getChunkOffset(localX, localZ);
        int size = header.getChunkSize(localX, localZ);
        
        if (offset + size > channel.size()) {
            return null;
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(size);
        channel.read(buffer, offset);
        buffer.flip();
        byte[] bucket = buffer.array();
        
        // Read 4-byte length header
        if (bucket.length < 4) return null;
        int exactLength = ((bucket[0] & 0xFF) << 24) | ((bucket[1] & 0xFF) << 16) | ((bucket[2] & 0xFF) << 8) | (bucket[3] & 0xFF);
        
        if (exactLength <= 0 || exactLength > bucket.length - 4) {
            return null;
        }
        
        // LRF stores timestamp at end of chunk data
        if (exactLength < 8) return null;
        
        int payloadSize = exactLength - 8;
        byte[] payload = new byte[payloadSize];
        System.arraycopy(bucket, 4, payload, 0, payloadSize);
        
        // Extract timestamp from end of data
        long timestamp = 0;
        if (bucket.length >= 8) {
            int tsOffset = 4 + exactLength - 8;
            timestamp = ((long) bucket[tsOffset] & 0xFF) << 56 |
                       ((long) bucket[tsOffset + 1] & 0xFF) << 48 |
                       ((long) bucket[tsOffset + 2] & 0xFF) << 40 |
                       ((long) bucket[tsOffset + 3] & 0xFF) << 32 |
                       ((long) bucket[tsOffset + 4] & 0xFF) << 24 |
                       ((long) bucket[tsOffset + 5] & 0xFF) << 16 |
                       ((long) bucket[tsOffset + 6] & 0xFF) << 8 |
                       ((long) bucket[tsOffset + 7] & 0xFF);
        }
        
        return new LRFChunkEntry(chunkX, chunkZ, payload, timestamp);
    }
    
    /**
     * Get total chunk count in region.
     */
    public int getChunkCount() {
        int count = 0;
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                if (header.hasChunk(x, z)) {
                    count++;
                }
            }
        }
        return count;
    }
    
    /**
     * Get LRF header.
     */
    public LRFHeader getHeader() {
        return header;
    }
    
    /**
     * Get file path.
     */
    public Path getFile() {
        return filePath;
    }
    
    /**
     * Close the file channel and ensure data is flushed to disk.
     * Override parent close() to add force() sync.
     */
    @Override
    public void close() throws IOException {
        synchronized (fileLock) {
            if (channel != null && channel.isOpen()) {
                channel.force(true);
                channel.close();
            }
            super.close();
        }
    }
    
    // Override other public methods if necessary...
    // boolean isOversized(...) - LRF supports large chunks natively (int size), so no oversized needed separately?
    // We should probably report false for oversized to standard checks.
}
