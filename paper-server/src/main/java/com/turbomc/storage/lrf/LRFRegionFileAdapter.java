package com.turbomc.storage.lrf;

import com.turbomc.compression.TurboCompressionService;
import com.turbomc.storage.cache.TurboCacheManager;
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
        byte[] bucket = buffer.array();
        
        // Read 4-byte length header
        if (bucket.length < 4) return null;
        int exactLength = ((bucket[0] & 0xFF) << 24) | ((bucket[1] & 0xFF) << 16) | ((bucket[2] & 0xFF) << 8) | (bucket[3] & 0xFF);
        
        // Validate length
        if (exactLength <= 0 || exactLength > bucket.length - 4) {
            String hexDump = "";
            for (int i = 0; i < Math.min(bucket.length, 16); i++) {
                hexDump += String.format("%02X ", bucket[i]);
            }
            System.err.println("[TurboMC][LRF] Critical Error reading chunk " + chunkX + "," + chunkZ + 
                " at offset " + offset + " (Size: " + size + ")");
            System.err.println("[TurboMC][LRF] Read Integer: " + exactLength);
            System.err.println("[TurboMC][LRF] First 16 bytes: " + hexDump);
            
            throw new IOException("Invalid LRF chunk length header: " + exactLength + " (Sector size: " + bucket.length + ")");
        }

        // LRF stores timestamp at end of chunk data (last 8 bytes)
        // We must strip this BEFORE decompression to assume payload is valid compressed block
        if (exactLength < 8) return null; 
        
        int payloadSize = exactLength - 8;
        byte[] payload = new byte[payloadSize];
        System.arraycopy(bucket, 4, payload, 0, payloadSize); // Offset 4 to skip length header

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
        // Uses primary compressor (LZ4 default or Zstd if configured)
        byte[] compressedData;
        try {
            compressedData = TurboCompressionService.getInstance().compress(nbtData);
        } catch (Exception e) {
            System.err.println("[TurboMC] Compression failed in LRFRegionFileAdapter: " + e.getMessage());
            compressedData = nbtData; // Fallback to uncompressed
        }
        
        // Size calculation (payload + 8 bytes timestamp)
        int size = compressedData.length + 8;
        int chunkX = chunkPos.x & 31;
        int chunkZ = chunkPos.z & 31;
        int index = LRFConstants.getChunkIndex(chunkX, chunkZ);

        synchronized (fileLock) {
            // Determine where to write - Append strategy
            long currentSize = channel.size();
            
            // Align write offset to 256 bytes
            long writeOffset = (currentSize + 255) & ~255;
            long padding = writeOffset - currentSize;
            
            if (padding > 0) {
                 // Zero-fill padding to prevent garbage data
                 // Optimization: reuse a cached zero buffer if possible, or small alloc
                 // For now, small alloc is fine for occasional padding
                 channel.position(currentSize);
                 channel.write(ByteBuffer.allocate((int)padding));
            }
            channel.position(writeOffset);

            // Zero-Copy Gathering Write
            // 1. Length Header (4 bytes)
            // LRF format requires an internal length header because the sector map is coarse (4KB alignment).
            // Length = CompressedData Size + Timestamp Size (8)
            int dataLength = compressedData.length + 8;
            ByteBuffer lenBuf = ByteBuffer.allocate(4);
            lenBuf.putInt(dataLength);
            lenBuf.flip();

            // 2. Data Buffer
            ByteBuffer dataBuf = ByteBuffer.wrap(compressedData);
            
            // 3. Timestamp Buffer
            ByteBuffer tsBuf = ByteBuffer.allocate(8);
            tsBuf.putLong(System.currentTimeMillis());
            tsBuf.flip();
            
            // Write payload efficiently
            channel.write(new ByteBuffer[]{lenBuf, dataBuf, tsBuf});
            
            // Update in-memory header
            // Total size on disk = 4 (len) + dataLength
            int totalSize = 4 + dataLength;
            header.setChunkData(chunkX, chunkZ, (int) writeOffset, totalSize);
            
            // Update On-Disk Header (Granular Write)
            // LRF optimization: Only write the specific modified entry (4 bytes)
            // instead of flushing the entire 8KB header.
            
            int offsetSectors = (int) (writeOffset / 256);
            int sizeSectors = (totalSize + 4095) / 4096;
            int entryValue = (offsetSectors << 8) | (sizeSectors & 0xFF);
            
            ByteBuffer entryBuf = ByteBuffer.allocate(4);
            entryBuf.putInt(entryValue);
            entryBuf.flip();
            
            long entryPos = LRFConstants.OFFSETS_TABLE_OFFSET + (index * 4L);
            channel.write(entryBuf, entryPos);
            
            // No force(true) - rely on OS page cache for performance
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
