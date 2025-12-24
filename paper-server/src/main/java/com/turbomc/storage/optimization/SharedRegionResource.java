package com.turbomc.storage.optimization;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import com.turbomc.storage.lrf.LRFHeader;
import com.turbomc.storage.lrf.LRFConstants;

/**
 * Shared resource management for a single region file.
 * Manages a single FileChannel and optional MMap buffer to ensure consistency.
 */
public class SharedRegionResource implements AutoCloseable {
    
    private final Path path;
    private final RandomAccessFile file;
    private final FileChannel channel;
    private final AtomicInteger refCount;
    private volatile MappedByteBuffer mappedBuffer;
    private final Object mmapLock = new Object();
    
    // Header caching for performance
    private volatile LRFHeader cachedHeader;
    private volatile long lastHeaderRefresh;
    private volatile long lastFileModified;
    private volatile long lastFileSize;
    private final Object headerLock = new Object();
    
    public SharedRegionResource(Path path) throws IOException {
        this.path = path;
        this.file = new RandomAccessFile(path.toFile(), "rw");
        this.channel = file.getChannel();
        this.refCount = new AtomicInteger(1);
    }
    
    public void acquire() {
        refCount.incrementAndGet();
    }
    
    public FileChannel getChannel() {
        return channel;
    }
    
    public Path getPath() {
        return path;
    }
    
    public MappedByteBuffer getOrCreateMappedBuffer(long size) throws IOException {
        if (mappedBuffer != null && mappedBuffer.limit() >= size) {
            return mappedBuffer;
        }
        
        synchronized (mmapLock) {
            if (mappedBuffer != null && mappedBuffer.limit() >= size) {
                return mappedBuffer;
            }
            
            // Map the entire file or specified size
            long mapSize = Math.max(size, channel.size());
            mappedBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, mapSize);
            return mappedBuffer;
        }
    }
    
    public MappedByteBuffer getMappedBuffer() {
        return mappedBuffer;
    }
    
    /**
     * Get the definitively accurate header for this region, with caching.
     */
    public LRFHeader getHeader() throws IOException {
        long currentModified;
        try {
            currentModified = java.nio.file.Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            currentModified = System.currentTimeMillis();
        }
        
        long currentSize = channel.size();
        LRFHeader header = cachedHeader;
        if (header != null && currentModified <= lastFileModified && 
            currentSize == lastFileSize && 
            (System.currentTimeMillis() - lastHeaderRefresh < 2000)) { // Increased TTL to 2s
            return header;
        }
        
        synchronized (headerLock) {
            // Double-check
            header = cachedHeader;
            if (header != null && currentModified <= lastFileModified && 
                (System.currentTimeMillis() - lastHeaderRefresh < 1000)) {
                return header;
            }
            
            byte[] headerData = new byte[LRFConstants.HEADER_SIZE];
            ByteBuffer headerBuffer = ByteBuffer.wrap(headerData);
            
            // Concurrent-safe read from channel
            int read = channel.read(headerBuffer, 0);
            if (read < LRFConstants.HEADER_SIZE) {
                // Try mmap fallback
                if (mappedBuffer != null) {
                    ByteBuffer slice = mappedBuffer.slice();
                    slice.limit(Math.min(LRFConstants.HEADER_SIZE, slice.capacity()));
                    slice.get(headerData);
                } else if (read <= 0 && channel.size() == 0) {
                    // New file, create empty header
                    return LRFHeader.createEmpty();
                } else {
                    throw new IOException("Failed to read definitive LRF header from " + path);
                }
            }
            
            header = LRFHeader.read(ByteBuffer.wrap(headerData));
            cachedHeader = header;
            lastFileModified = currentModified;
            lastFileSize = currentSize;
            lastHeaderRefresh = System.currentTimeMillis();
            return header;
        }
    }
    
    /**
     * Invalidate the cached header, forcing a re-read on next access.
     */
    public void invalidateHeader() {
        synchronized (headerLock) {
            cachedHeader = null;
            lastHeaderRefresh = 0;
        }
    }

    @Override
    public void close() throws IOException {
        if (refCount.decrementAndGet() <= 0) {
            if (mappedBuffer != null) {
                // Should ideally unmap, but Java's MappedByteBuffer unmapping is complex
                mappedBuffer = null;
            }
            channel.close();
            file.close();
        }
    }
    
    public boolean isClosed() {
        return refCount.get() <= 0;
    }
}
