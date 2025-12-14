package com.turbomc.storage;

import com.turbomc.compression.TurboCompressionService;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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
        
        int chunkX = chunkPos.x & 31;
        int chunkZ = chunkPos.z & 31;
        
        // 1. Check Global Cache (L1)
        byte[] cached = TurboCacheManager.getInstance().get(filePath, chunkX, chunkZ);
        if (cached != null) {
            return new DataInputStream(new ByteArrayInputStream(cached));
        }

        if (!header.hasChunk(chunkX, chunkZ)) {
            return null;
        }

        // 2. Read from Disk (L2)
        long offset = header.getChunkOffset(chunkX, chunkZ);
        int size = header.getChunkSize(chunkX, chunkZ);

        if (offset + size > channel.size()) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.allocate(size);
        channel.read(buffer, offset);
        buffer.flip();
        byte[] diskData = buffer.array();

        // LRF stores timestamp at end of chunk data (last 8 bytes)
        // We must strip this BEFORE decompression to assume payload is valid compressed block
        if (diskData.length < 8) return null; // Should not happen if size is correct from header

        int payloadSize = diskData.length - 8;
        byte[] payload = new byte[payloadSize];
        System.arraycopy(diskData, 0, payload, 0, payloadSize);

        byte[] nbtData;
        if (header.getCompressionType() == LRFConstants.COMPRESSION_NONE) {
            nbtData = payload;
        } else {
            nbtData = TurboCompressionService.getInstance().decompress(payload);
        }

        // 3. Update Cache
        // Cache pure NBT data
        TurboCacheManager.getInstance().put(filePath, chunkX, chunkZ, nbtData);
        
        return new DataInputStream(new ByteArrayInputStream(nbtData));
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
        // Compress data
        // Uses primary compressor (LZ4 default) configured in TurboCompressionService
        byte[] compressedData = TurboCompressionService.getInstance().compress(nbtData);
        
        // Add timestamp space (8 bytes)
        ByteBuffer dataToWrite = ByteBuffer.allocate(compressedData.length + 8);
        dataToWrite.put(compressedData);
        dataToWrite.putLong(System.currentTimeMillis());
        dataToWrite.flip();

        int size = dataToWrite.remaining();
        int chunkX = chunkPos.x & 31;
        int chunkZ = chunkPos.z & 31;

        synchronized (fileLock) {
            // Determine where to write
            // Simple strategy: Always append to end
            long currentSize = channel.size();
            
            // CRITICAL: LRF Header uses 256-byte units for offsets.
            // We MUST align the write offset to 256 bytes.
            long writeOffset = (currentSize + 255) & ~255;
            long padding = writeOffset - currentSize;
            
            // Advance channel to write position (handling padding via gap if we just seek?)
            // No, channel.position(newPos) does NOT fill gap with zeroes automatically on some OS/FS?
            // Usually it does (sparse files). But random bytes might appear? 
            // Better write zeroes explicitly if padding is small.
            if (padding > 0) {
                 channel.position(currentSize);
                 channel.write(ByteBuffer.allocate((int)padding));
            }
            channel.position(writeOffset); // Ensure we are at aligned offset

            // Zero-Copy Gathering Write
            // 1. Data Buffer (Directly from compression service if possible, currently we have byte[])
            ByteBuffer dataBuf = ByteBuffer.wrap(compressedData);
            // 2. Timestamp Buffer (8 bytes)
            ByteBuffer tsBuf = ByteBuffer.allocate(8);
            tsBuf.putLong(System.currentTimeMillis());
            tsBuf.flip();
            
            // Write both buffers in one OS call
            channel.write(new ByteBuffer[]{dataBuf, tsBuf});
            
            // Update header
            // size is passed in bytes; header converts to 4KB sectors internally (rounding up)
            header.setChunkData(chunkX, chunkZ, (int) writeOffset, size);
            
            // Write updated header to disk
            ByteBuffer headerBuf = ByteBuffer.allocate(LRFConstants.HEADER_SIZE);
            header.write(headerBuf);
            headerBuf.flip();
            channel.write(headerBuf, 0);
            
            // REMOVED: channel.force(false); 
            // Performance Fix: Do NOT force disk sync on every chunk write. 
            // Reliance on OS page cache is standard behavior for RegionFiles. 
            // Sync happens on save-all / close.
        }
    }

    @Override
    public void flush() throws IOException {
        channel.force(true);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
    
    @Override
    public boolean doesChunkExist(ChunkPos chunkPos) {
        return header.hasChunk(chunkPos.x & 31, chunkPos.z & 31);
    }
    
    // Override other public methods if necessary...
    // boolean isOversized(...) - LRF supports large chunks natively (int size), so no oversized needed separately?
    // We should probably report false for oversized to standard checks.
}
