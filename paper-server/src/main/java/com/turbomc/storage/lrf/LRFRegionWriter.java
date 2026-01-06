package com.turbomc.storage.lrf;

import com.turbomc.compression.TurboCompressionService;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.StampedLock;
import com.turbomc.storage.optimization.SharedRegionResource;

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
    private final SharedRegionResource sharedResource;
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
    
    // StampedLock for Fase 4 I/O optimization
    private final StampedLock headerLock = new StampedLock();
    
    private static boolean verbose = false;

    public static void setVerbose(boolean value) {
        verbose = value;
    }

    public static boolean isVerbose() {
        return verbose;
    }

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
        this.sharedResource = null;
        this.compressionType = compressionType;
        this.chunks = new ArrayList<>();
        this.headerWritten = false;
        
        // Final field initialization
        this.writeBuffer = ByteBuffer.allocate(LRFConstants.STREAM_BUFFER_SIZE);
        this.bytesWritten = new AtomicLong(0);
        this.chunksCompressed = new AtomicLong(0);
        this.compressionTime = new AtomicLong(0);
        
        initializeWriter();
    }
    
    /**
     * Create writer using a shared resource.
     */
    public LRFRegionWriter(SharedRegionResource resource, int compressionType) throws IOException {
        this.filePath = resource.getPath();
        this.file = null; 
        this.channel = resource.getChannel();
        this.sharedResource = resource;
        this.compressionType = compressionType;
        this.chunks = new ArrayList<>();
        this.headerWritten = false;
        
        // Final field initialization
        this.writeBuffer = ByteBuffer.allocate(LRFConstants.STREAM_BUFFER_SIZE);
        this.bytesWritten = new AtomicLong(0);
        this.chunksCompressed = new AtomicLong(0);
        this.compressionTime = new AtomicLong(0);
        
        resource.acquire();
        initializeWriter();
    }
    
    private void initializeWriter() throws IOException {
        // Initialize header
        if (channel.size() < LRFConstants.HEADER_SIZE) {
            if (file != null) {
                file.setLength(LRFConstants.HEADER_SIZE);
            } else {
                channel.truncate(LRFConstants.HEADER_SIZE);
            }
            this.streamingHeader = new LRFHeader(LRFConstants.FORMAT_VERSION, 0, compressionType);
        } else {
            // Load existing header to avoid overwriting
            ByteBuffer headerBuffer = ByteBuffer.allocate(LRFConstants.HEADER_SIZE);
            channel.read(headerBuffer, 0);
            headerBuffer.flip();
            this.streamingHeader = LRFHeader.read(headerBuffer);
            
            // SET POSITION TO END FOR APPEND (Aligned to 256 bytes)
            long size = channel.size();
            long alignedSize = (size % 256 == 0) ? size : (size / 256 + 1) * 256;
            if (alignedSize > size) {
                channel.position(size);
                channel.write(ByteBuffer.allocate((int)(alignedSize - size)));
            }
            channel.position(alignedSize);
        }

        // Initialize performance components
        this.streamingMode = true; // Default to streaming for updates
        
        if (verbose) {
            System.out.println("[TurboMC] Opened LRF region for update: " + filePath.getFileName() + 
                             " (size: " + channel.size() + ")" + (sharedResource != null ? " [SHARED]" : ""));
        }
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
        
        if (verbose) {
            System.out.println("[TurboMC] Enabled streaming mode for " + filePath.getFileName());
        }
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
    public void writeChunkStreaming(LRFChunkEntry chunk) throws IOException {
        long startTime = System.nanoTime();
        com.turbomc.util.BufferPool pool = com.turbomc.util.BufferPool.getInstance();
        byte[] rawData = chunk.getData();
        byte[] dataToCompress = pool.acquire(rawData.length + 8);
        long stamp = 0;

        try {
            System.arraycopy(rawData, 0, dataToCompress, 0, rawData.length);
            long ts = chunk.getTimestamp();
            dataToCompress[rawData.length]     = (byte) (ts >>> 56);
            dataToCompress[rawData.length + 1] = (byte) (ts >>> 48);
            dataToCompress[rawData.length + 2] = (byte) (ts >>> 40);
            dataToCompress[rawData.length + 3] = (byte) (ts >>> 32);
            dataToCompress[rawData.length + 4] = (byte) (ts >>> 24);
            dataToCompress[rawData.length + 5] = (byte) (ts >>> 16);
            dataToCompress[rawData.length + 6] = (byte) (ts >>> 8);
            dataToCompress[rawData.length + 7] = (byte) ts;
            
            chunk.release();

            int actualCompressionType = compressionType;
            byte[] compressedData;
            
            if (compressionType == LRFConstants.COMPRESSION_NONE) {
                compressedData = new byte[rawData.length + 8];
                System.arraycopy(dataToCompress, 0, compressedData, 0, compressedData.length);
            } else {
                try {
                    compressedData = TurboCompressionService.getInstance().compress(dataToCompress, 0, rawData.length + 8);
                } catch (Exception e) {
                    System.err.println("[TurboMC][LRF] Compression failed for " + chunk.getChunkX() + "," + chunk.getChunkZ() + ": " + e.getMessage());
                    compressedData = new byte[rawData.length + 8];
                    System.arraycopy(dataToCompress, 0, compressedData, 0, compressedData.length);
                    actualCompressionType = LRFConstants.COMPRESSION_NONE;
                }
            }
        
            long currentPos;
            stamp = headerLock.readLock(); // Simplification: Use read lock instead of optimistic for sequential write coordination
            try {
                currentPos = channel.position();
                
                // Check alignment and padding
                if (currentPos % 256 != 0) {
                    long alignedPos = (currentPos / 256 + 1) * 256;
                    int paddingSize = (int)(alignedPos - currentPos);
                    byte[] padding = new byte[paddingSize];
                    channel.write(ByteBuffer.wrap(padding));
                    currentPos = alignedPos;
                }
                
                if (currentPos < LRFConstants.HEADER_SIZE) {
                    int paddingSize = (int)(LRFConstants.HEADER_SIZE - currentPos);
                    byte[] padding = new byte[paddingSize];
                    channel.write(ByteBuffer.wrap(padding));
                    currentPos = LRFConstants.HEADER_SIZE;
                }
            } finally {
                headerLock.unlockRead(stamp);
                stamp = 0;
            }
            
            // Upgrade to write lock for header modifications and channel write
            stamp = headerLock.writeLock();
            try {
                // Write length header (5 bytes: 4 length + 1 compression type)
                int totalLength = 4 + 1 + compressedData.length;
                byte[] lengthHeader = pool.acquire(5);
                try {
                    lengthHeader[0] = (byte) (totalLength >>> 24);
                    lengthHeader[1] = (byte) (totalLength >>> 16);
                    lengthHeader[2] = (byte) (totalLength >>> 8);
                    lengthHeader[3] = (byte) totalLength;
                    lengthHeader[4] = (byte) actualCompressionType;
                    channel.write(ByteBuffer.wrap(lengthHeader, 0, 5));
                } finally {
                    pool.release(lengthHeader);
                }
                
                // Write chunk data with buffer
                writeWithBuffer(compressedData);
                
                // Update streaming header
                streamingHeader.setChunkData(
                    chunk.getChunkX(),
                    chunk.getChunkZ(),
                    (int) currentPos,
                    totalLength
                );
            } finally {
                headerLock.unlockWrite(stamp);
                stamp = 0;
            }
        } catch (IOException e) {
            if (stamp != 0 && StampedLock.isWriteLockStamp(stamp)) {
                headerLock.unlockWrite(stamp);
            }
            throw e;
        } finally {
            pool.release(dataToCompress);
        }
        
        // Update statistics
        chunksCompressed.incrementAndGet();
        compressionTime.addAndGet(System.nanoTime() - startTime);
        
        // Reset headerWritten flag when adding new data after a flush
        headerWritten = false;
    }

    /**
     * Add a chunk that is already compressed.
     * Prevents double-compression when using ChunkBatchSaver.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @param compressedData The fully compressed chunk data (including timestamp)
     */
    public void addCompressedChunk(int chunkX, int chunkZ, byte[] compressedData) throws IOException {
        if (!streamingMode) {
             throw new UnsupportedOperationException("Compressed chunks only supported in streaming mode");
        }

        long stamp = 0;
        try {
             long currentPos;
             stamp = headerLock.readLock();
             try {
                 currentPos = channel.position();
                 
                 // Check alignment and padding
                 if (currentPos % 256 != 0) {
                     long alignedPos = (currentPos / 256 + 1) * 256;
                     int paddingSize = (int)(alignedPos - currentPos);
                     byte[] padding = new byte[paddingSize];
                     channel.write(ByteBuffer.wrap(padding));
                     currentPos = alignedPos;
                 }
                 
                 if (currentPos < LRFConstants.HEADER_SIZE) {
                     int paddingSize = (int)(LRFConstants.HEADER_SIZE - currentPos);
                     byte[] padding = new byte[paddingSize];
                     channel.write(ByteBuffer.wrap(padding));
                     currentPos = LRFConstants.HEADER_SIZE;
                 }
             } finally {
                 headerLock.unlockRead(stamp);
                 stamp = 0;
             }
             
             // Upgrade to write lock for header modifications and channel write
             stamp = headerLock.writeLock();
             try {
                 // Write length header (5 bytes: 4 length + 1 compression type)
                 int totalLength = 4 + 1 + compressedData.length;
                 // Use basic allocation for small header to avoid pool overhead/contention
                 ByteBuffer lengthBuffer = ByteBuffer.allocate(5);
                 lengthBuffer.putInt(totalLength);
                 lengthBuffer.put((byte) compressionType);
                 lengthBuffer.flip();
                 channel.write(lengthBuffer);
                 
                 // Write chunk data with buffer
                 writeWithBuffer(compressedData);
                 
                 // Update streaming header
                 streamingHeader.setChunkData(
                     chunkX,
                     chunkZ,
                     (int) currentPos,
                     totalLength
                 );
             } finally {
                 headerLock.unlockWrite(stamp);
                 stamp = 0;
             }
        } catch (IOException e) {
            if (stamp != 0 && StampedLock.isWriteLockStamp(stamp)) {
                headerLock.unlockWrite(stamp);
            }
            throw e;
        }
        
        // Update statistics (approximate)
        chunksCompressed.incrementAndGet(); 
        headerWritten = false;
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
    public synchronized void flush() throws IOException {
        flush(true);
    }

    public synchronized void flush(boolean force) throws IOException {
        if (headerWritten && !streamingMode) {
            return; // Already flushed batch
        }
        
        if (streamingMode) {
            flushStreaming(force);
        } else {
            flushBatch();
            if (force) channel.force(true);
        }
    }
    
    /**
     * Flush streaming mode - write header with final offsets.
     */
    private synchronized void flushStreaming(boolean force) throws IOException {
        LRFHeader finalHeader = new LRFHeader(
            LRFConstants.FORMAT_VERSION,
            streamingHeader.countChunks(),
            compressionType,
            streamingHeader.getOffsets(),
            streamingHeader.getSizes()
        );
        
        // Write header at beginning of file
        ByteBuffer headerBuffer = ByteBuffer.allocate(LRFConstants.HEADER_SIZE);
        finalHeader.write(headerBuffer);
        headerBuffer.flip();
        channel.write(headerBuffer, 0);
        
        // CRITICAL: Force sync to disk to prevent corruption
        if (force) {
            channel.force(true);
        }
        
        headerWritten = true;
        
        if (verbose) {
            System.out.println("[TurboMC] Streaming flush: " + chunksCompressed.get() + 
                             " chunks to " + filePath.getFileName() + " (force=" + force + ")");
        }
    }
    
    /**
     * Flush batch mode - write all buffered chunks.
     */
    private synchronized void flushBatch() throws IOException {
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
            
            byte[] dataToWrite = chunk.getData();
            
            // Compress if needed with error handling
            byte[] compressedData;
            if (compressionType == LRFConstants.COMPRESSION_NONE) {
                compressedData = dataToWrite;
            } else {
                try {
                    compressedData = TurboCompressionService.getInstance().compress(dataToWrite);
                } catch (com.turbomc.compression.CompressionException e) {
                    System.err.println("[TurboMC] Compression failed for chunk at " + chunk.getChunkX() + "," + chunk.getChunkZ() + ", using uncompressed data");
                    // Fallback to uncompressed data
                    compressedData = dataToWrite;
                }
            }
            
            // Align currentOffset to 256 bytes for next chunk
            if (currentOffset % 256 != 0) {
                currentOffset = (currentOffset / 256 + 1) * 256;
                channel.position(currentOffset);
            }
            
            // Write length header (5 bytes) - Total length = 4 + 1 + compressedData.length
            int totalLength = 4 + 1 + compressedData.length;
            ByteBuffer lengthBuffer = ByteBuffer.allocate(5);
            lengthBuffer.putInt(totalLength);
            lengthBuffer.put((byte)compressionType);
            lengthBuffer.flip();
            channel.write(lengthBuffer);
            
            // Write chunk data
            writeWithBuffer(compressedData);
            
            // Update header with offset and size
            header.setChunkData(
                chunk.getChunkX(),
                chunk.getChunkZ(),
                currentOffset,
                totalLength
            );
            
            currentOffset += totalLength;
            
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
        
        // CRITICAL: Force sync to disk to prevent corruption
        channel.force(true);
        
        headerWritten = true;
        
        System.out.println("[TurboMC] Batch flush: " + chunks.size() + " chunks to " + 
                         " (" + currentOffset + " bytes)");
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
            if (sharedResource != null) {
                sharedResource.close();
            } else {
                if (channel != null && channel.isOpen()) {
                    channel.close();
                }
                if (file != null) {
                    file.close();
                }
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
