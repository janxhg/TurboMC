package com.turbomc.compression;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Zlib implementation of the Compressor interface.
 * Compatible with vanilla Minecraft compression format.
 */
public class ZlibCompressor implements Compressor {
    private static final byte MAGIC_BYTE = 0x78; // FIXED: Match service expectation (zlib default)
    
    private final int level;
    
    // ThreadLocal caches to avoid allocation overhead (native memory init is slow)
    private final ThreadLocal<Deflater> deflaterCache;
    private final ThreadLocal<Inflater> inflaterCache;
    private final ThreadLocal<byte[]> bufferCache = ThreadLocal.withInitial(() -> new byte[8192]);
    
    // FIXED: Add cleanup method to prevent memory leaks
    public void cleanup() {
        deflaterCache.remove();
        inflaterCache.remove();
        bufferCache.remove();
    }
    
    public ZlibCompressor(int level) {
        this.level = Math.max(1, Math.min(9, level));
        this.deflaterCache = ThreadLocal.withInitial(() -> new Deflater(this.level));
        this.inflaterCache = ThreadLocal.withInitial(Inflater::new);
    }
    
    @Override
    public byte[] compress(byte[] data) throws CompressionException {
        return compress(data, 0, data != null ? data.length : 0);
    }

    @Override
    public byte[] compress(byte[] data, int offset, int length) throws CompressionException {
        if (data == null || length == 0) {
            return new byte[]{MAGIC_BYTE, 0, 0, 0, 0};
        }
        
        try {
            Deflater deflater = deflaterCache.get();
            deflater.reset();
            deflater.setLevel(level);
            deflater.setInput(data, offset, length);
            deflater.finish();
            
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream(length / 2);
            byte[] buffer = bufferCache.get();
            
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                outputStream.write(buffer, 0, count);
            }
            
            byte[] compressed = outputStream.toByteArray();
            
            ByteBuffer result = ByteBuffer.allocate(1 + 4 + compressed.length);
            result.put(MAGIC_BYTE);
            result.putInt(length);
            result.put(compressed);
            
            return result.array();
        } catch (Exception e) {
            throw new CompressionException("Zlib compression failed", e);
        }
    }
    
    @Override
    public byte[] decompress(byte[] compressed) throws CompressionException {
        if (compressed == null || compressed.length < 5) {
            throw new CompressionException("Invalid compressed data: too short");
        }
        
        try {
            ByteBuffer buffer = ByteBuffer.wrap(compressed);
            
            // Read magic byte
            byte magic = buffer.get();
            if (magic != MAGIC_BYTE) {
                throw new CompressionException("Invalid magic byte for Zlib: " + magic);
            }
            
            // Read original size
            int originalSize = buffer.getInt();
            
            if (originalSize == 0) {
                return new byte[0];
            }
            
            // Decompress
            // We can avoid copying compressedData if we use setter with offset/length
            int compressedSize = buffer.remaining();
            
            Inflater inflater = inflaterCache.get();
            inflater.reset();
            inflater.setInput(compressed, 5, compressedSize); // Header is 5 bytes
            
            byte[] decompressed = new byte[originalSize];
            int resultLength = inflater.inflate(decompressed);
            
            if (resultLength != originalSize) {
                // Check if there is more? Zlib sometimes has issues if buffer isn't full?
                // But generally explicit size is exact.
                
                // Fallback loop if needed? Usually single inflate is enough if output buffer is large enough.
                while (!inflater.finished() && !inflater.needsInput() && resultLength < originalSize) {
                    resultLength += inflater.inflate(decompressed, resultLength, originalSize - resultLength);
                }
                
                if (resultLength != originalSize) {
                    throw new CompressionException("Decompressed size mismatch: expected " + originalSize + ", got " + resultLength);
                }
            }
            
            return decompressed;
        } catch (Exception e) {
            throw new CompressionException("Zlib decompression failed", e);
        }
    }
    
    @Override
    public String getName() {
        return "Zlib (Optimized)";
    }
    
    @Override
    public int getCompressionLevel() {
        return level;
    }
    
    @Override
    public byte getMagicByte() {
        return MAGIC_BYTE;
    }
}
