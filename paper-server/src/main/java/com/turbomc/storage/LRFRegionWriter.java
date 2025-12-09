package com.turbomc.storage;

import com.turbomc.compression.TurboCompressionService;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes LRF (Linear Region Format) files.
 * Provides efficient sequential chunk writing with automatic compression.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class LRFRegionWriter implements AutoCloseable {
    
    private final Path filePath;
    private final RandomAccessFile file;
    private final FileChannel channel;
    private final int compressionType;
    private final List<LRFChunkEntry> chunks;
    private boolean headerWritten;
    
    /**
     * Create a new LRF file for writing.
     * 
     * @param filePath Path to .lrf file
     * @param compressionType Compression algorithm to use
     * @throws IOException if file cannot be created
     */
    public LRFRegionWriter(Path filePath, int compressionType) throws IOException {
        this.filePath = filePath;
        this.file = new RandomAccessFile(filePath.toFile(), "rw");
        this.channel = file.getChannel();
        this.compressionType = compressionType;
        this.chunks = new ArrayList<>();
        this.headerWritten = false;
        
        // Reserve space for header
        file.setLength(LRFConstants.HEADER_SIZE);
        
        System.out.println("[TurboMC] Creating LRF region: " + filePath.getFileName() + 
                         " (compression: " + LRFConstants.getCompressionName(compressionType) + ")");
    }
    
    /**
     * Create LRF file with LZ4 compression (default).
     */
    public LRFRegionWriter(Path filePath) throws IOException {
        this(filePath, LRFConstants.COMPRESSION_LZ4);
    }
    
    /**
     * Add a chunk to be written.
     * Chunks are buffered and written when flush() or close() is called.
     * 
     * @param chunk Chunk entry with uncompressed data
     */
    public void addChunk(LRFChunkEntry chunk) {
        if (headerWritten) {
            throw new IllegalStateException("Cannot add chunks after header is written");
        }
        chunks.add(chunk);
    }
    
    /**
     * Add a chunk with raw NBT data.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param nbtData Uncompressed NBT data
     */
    public void addChunk(int chunkX, int chunkZ, byte[] nbtData) {
        addChunk(new LRFChunkEntry(chunkX, chunkZ, nbtData));
    }
    
    /**
     * Write all buffered chunks to file.
     * This is called automatically by close() but can be called manually.
     * 
     * @throws IOException if write fails
     */
    public void flush() throws IOException {
        if (headerWritten) {
            return; // Already flushed
        }
        
        // Create header
        LRFHeader header = new LRFHeader(
            LRFConstants.FORMAT_VERSION,
            chunks.size(),
            compressionType
        );
        
        // Write chunks sequentially and track offsets
        int currentOffset = LRFConstants.HEADER_SIZE;
        
        for (LRFChunkEntry chunk : chunks) {
            byte[] data = chunk.getData();
            
            // Append timestamp to data
            ByteBuffer dataWithTimestamp = ByteBuffer.allocate(data.length + 8);
            dataWithTimestamp.put(data);
            dataWithTimestamp.putLong(chunk.getTimestamp());
            dataWithTimestamp.flip();
            
            byte[] dataToWrite = new byte[dataWithTimestamp.remaining()];
            dataWithTimestamp.get(dataToWrite);
            
            // Compress if needed
            byte[] compressedData;
            if (compressionType == LRFConstants.COMPRESSION_NONE) {
                compressedData = dataToWrite;
            } else {
                compressedData = TurboCompressionService.getInstance().compress(dataToWrite);
            }
            
            // Write chunk data
            ByteBuffer buffer = ByteBuffer.wrap(compressedData);
            channel.write(buffer, currentOffset);
            
            // Update header with offset and size
            header.setChunkData(
                chunk.getChunkX(),
                chunk.getChunkZ(),
                currentOffset,
                compressedData.length
            );
            
            currentOffset += compressedData.length;
        }
        
        // Write header at beginning of file
        ByteBuffer headerBuffer = ByteBuffer.allocate(LRFConstants.HEADER_SIZE);
        header.write(headerBuffer);
        headerBuffer.flip();
        channel.write(headerBuffer, 0);
        
        // Truncate file to actual size
        channel.truncate(currentOffset);
        
        headerWritten = true;
        
        System.out.println("[TurboMC] Wrote " + chunks.size() + " chunks to " + 
                         filePath.getFileName() + " (" + currentOffset + " bytes)");
    }
    
    /**
     * Get number of chunks buffered.
     * 
     * @return Chunk count
     */
    public int getChunkCount() {
        return chunks.size();
    }
    
    /**
     * Get file path.
     * 
     * @return Path to LRF file
     */
    public Path getFilePath() {
        return filePath;
    }
    
    /**
     * Get compression type.
     * 
     * @return Compression algorithm constant
     */
    public int getCompressionType() {
        return compressionType;
    }
    
    @Override
    public void close() throws IOException {
        try {
            // Flush any pending chunks
            if (!headerWritten && !chunks.isEmpty()) {
                flush();
            }
        } finally {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (file != null) {
                file.close();
            }
        }
    }
    
    @Override
    public String toString() {
        return "LRFRegionWriter{" +
               "file=" + filePath.getFileName() +
               ", chunks=" + chunks.size() +
               ", compression=" + LRFConstants.getCompressionName(compressionType) +
               ", written=" + headerWritten +
               '}';
    }
}
