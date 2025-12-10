package com.turbomc.storage;

import com.turbomc.compression.TurboCompressionService;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
    
    // Performance optimizations
    private final ByteBuffer writeBuffer;
    private final AtomicLong bytesWritten;
    private final AtomicLong chunksCompressed;
    private final AtomicLong compressionTime;
    
    // Streaming support
    private boolean streamingMode;
    private LRFHeader streamingHeader;
    
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
        
        // Initialize performance components
        this.writeBuffer = ByteBuffer.allocate(LRFConstants.STREAM_BUFFER_SIZE);
        this.bytesWritten = new AtomicLong(0);
        this.chunksCompressed = new AtomicLong(0);
        this.compressionTime = new AtomicLong(0);
        this.streamingMode = false;
        
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
     * Enable streaming mode for large files.
     * Writes chunks immediately instead of buffering.
     */
    public void enableStreaming() throws IOException {
        if (headerWritten) {
            throw new IllegalStateException("Cannot enable streaming after header is written");
        }
        
        this.streamingMode = true;
        this.streamingHeader = new LRFHeader(
            LRFConstants.FORMAT_VERSION, 0, compressionType
        );
        
        System.out.println("[TurboMC] Enabled streaming mode for " + filePath.getFileName());
    }
    
    /**
     * Add a chunk to be written.
     * In streaming mode, writes immediately.
     * In batch mode, buffers for later writing.
     * 
     * @param chunk Chunk entry with uncompressed data
     */
    public void addChunk(LRFChunkEntry chunk) throws IOException {
        if (streamingMode) {
            writeChunkStreaming(chunk);
        } else {
            if (headerWritten) {
                throw new IllegalStateException("Cannot add chunks after header is written");
            }
            chunks.add(chunk);
        }
    }
    
    /**
     * Add a chunk with raw NBT data.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param nbtData Uncompressed NBT data
     * @throws IOException if chunk cannot be added
     */
    public void addChunk(int chunkX, int chunkZ, byte[] nbtData) throws IOException {
        addChunk(new LRFChunkEntry(chunkX, chunkZ, nbtData));
    }
    
    /**
     * Write chunk in streaming mode.
     */
    private void writeChunkStreaming(LRFChunkEntry chunk) throws IOException {
        long startTime = System.nanoTime();
        
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
        
        // Get current file position for offset
        long currentPos = channel.position();
        if (currentPos < LRFConstants.HEADER_SIZE) {
            currentPos = LRFConstants.HEADER_SIZE;
            channel.position(currentPos);
        }
        
        // Write chunk data with buffer
        writeWithBuffer(compressedData);
        
        // Update streaming header
        streamingHeader.setChunkData(
            chunk.getChunkX(),
            chunk.getChunkZ(),
            (int) currentPos,
            compressedData.length
        );
        
        // Update statistics
        chunksCompressed.incrementAndGet();
        compressionTime.addAndGet(System.nanoTime() - startTime);
    }
    
    /**
     * Helper method to write with buffer reuse.
     */
    private void writeWithBuffer(byte[] data) throws IOException {
        int remaining = data.length;
        int offset = 0;
        
        while (remaining > 0) {
            int chunkSize = Math.min(remaining, writeBuffer.capacity());
            
            writeBuffer.clear();
            writeBuffer.put(data, offset, chunkSize);
            writeBuffer.flip();
            
            int written = channel.write(writeBuffer);
            bytesWritten.addAndGet(written);
            
            offset += chunkSize;
            remaining -= chunkSize;
        }
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
        
        if (streamingMode) {
            flushStreaming();
        } else {
            flushBatch();
        }
    }
    
    /**
     * Flush streaming mode - write header with final offsets.
     */
    private void flushStreaming() throws IOException {
        // Update chunk count in header
        LRFHeader finalHeader = new LRFHeader(
            LRFConstants.FORMAT_VERSION,
            (int) chunksCompressed.get(),
            compressionType,
            streamingHeader.getOffsets(),
            streamingHeader.getSizes()
        );
        
        // Write header at beginning of file
        ByteBuffer headerBuffer = ByteBuffer.allocate(LRFConstants.HEADER_SIZE);
        finalHeader.write(headerBuffer);
        headerBuffer.flip();
        channel.write(headerBuffer, 0);
        
        headerWritten = true;
        
        System.out.println("[TurboMC] Streaming flush: " + chunksCompressed.get() + 
                         " chunks to " + filePath.getFileName());
    }
    
    /**
     * Flush batch mode - write all buffered chunks.
     */
    private void flushBatch() throws IOException {
        // Create header
        LRFHeader header = new LRFHeader(
            LRFConstants.FORMAT_VERSION,
            chunks.size(),
            compressionType
        );
        
        // Write chunks sequentially and track offsets
        int currentOffset = LRFConstants.HEADER_SIZE;
        
        for (LRFChunkEntry chunk : chunks) {
            long startTime = System.nanoTime();
            
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
            writeWithBuffer(compressedData);
            
            // Update header with offset and size
            header.setChunkData(
                chunk.getChunkX(),
                chunk.getChunkZ(),
                currentOffset,
                compressedData.length
            );
            
            currentOffset += compressedData.length;
            
            // Update statistics
            chunksCompressed.incrementAndGet();
            compressionTime.addAndGet(System.nanoTime() - startTime);
        }
        
        // Write header at beginning of file
        ByteBuffer headerBuffer = ByteBuffer.allocate(LRFConstants.HEADER_SIZE);
        header.write(headerBuffer);
        headerBuffer.flip();
        channel.write(headerBuffer, 0);
        
        // Truncate file to actual size
        channel.truncate(currentOffset);
        
        headerWritten = true;
        
        System.out.println("[TurboMC] Batch flush: " + chunks.size() + " chunks to " + 
                         filePath.getFileName() + " (" + currentOffset + " bytes)");
    }
    
    /**
     * Get compression type.
     * 
     * @return Compression algorithm constant
     */
    public int getCompressionType() {
        return compressionType;
    }
    
    /**
     * Get compression statistics.
     */
    public CompressionStats getCompressionStats() {
        return new CompressionStats(
            chunksCompressed.get(),
            bytesWritten.get(),
            compressionTime.get()
        );
    }
    
    /**
     * Compression statistics holder.
     */
    public static class CompressionStats {
        public final long chunksCompressed;
        public final long bytesWritten;
        public final long totalCompressionTime;
        
        public CompressionStats(long chunks, long bytes, long time) {
            this.chunksCompressed = chunks;
            this.bytesWritten = bytes;
            this.totalCompressionTime = time;
        }
        
        public double getAvgCompressionTime() {
            return chunksCompressed > 0 ? (double) totalCompressionTime / chunksCompressed / 1_000_000 : 0;
        }
        
        @Override
        public String toString() {
            return String.format("CompressionStats{chunks=%d, bytes=%.1fMB, avgTime=%.2fms}",
                    chunksCompressed, bytesWritten / 1024.0 / 1024.0, getAvgCompressionTime());
        }
    }
    
    @Override
    public void close() throws IOException {
        try {
            // Flush any pending chunks
            if (!headerWritten && (!chunks.isEmpty() || streamingMode)) {
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
               ", chunks=" + (streamingMode ? chunksCompressed.get() : chunks.size()) +
               ", compression=" + LRFConstants.getCompressionName(compressionType) +
               ", streaming=" + streamingMode +
               ", stats=" + getCompressionStats() +
               '}';
    }
}
