package com.turbomc.compression;

import com.github.luben.zstd.Zstd;
import java.nio.ByteBuffer;

/**
 * Zstandard compressor implementation using zstd-jni.
 * High performance, high compression ratio.
 */
public class ZstdCompressor implements Compressor {

    private final int level;

    public ZstdCompressor(int level) {
        // Zstd levels: 1 (fast) to 22 (ultra). Default typical is 3.
        // Clamp to valid range.
        this.level = Math.max(1, Math.min(22, level));
    }

    @Override
    public byte[] compress(byte[] data) throws CompressionException {
        return compress(data, 0, data != null ? data.length : 0);
    }

    @Override
    public byte[] compress(byte[] data, int offset, int length) throws CompressionException {
        if (data == null || length == 0) {
            return new byte[]{getMagicByte()};
        }
        
        com.turbomc.util.BufferPool pool = com.turbomc.util.BufferPool.getInstance();
        long maxCompressedSize = Zstd.compressBound(length);
        if (maxCompressedSize > Integer.MAX_VALUE) {
            throw new CompressionException("Data too large for single array compression");
        }
        
        byte[] output = pool.acquire((int) maxCompressedSize + 1);
        try {
            output[0] = getMagicByte();
            long compressedSize = Zstd.compressByteArray(output, 1, output.length - 1, data, offset, length, level);
            
            if (Zstd.isError(compressedSize)) {
                 throw new CompressionException("Zstd compression error: " + Zstd.getErrorName(compressedSize));
            }
            
            byte[] exact = new byte[(int)compressedSize + 1];
            System.arraycopy(output, 0, exact, 0, (int)compressedSize + 1);
            return exact;
            
        } catch (Exception e) {
            throw new CompressionException("Zstd compression failed", e);
        } finally {
            pool.release(output);
        }
    }

    @Override
    public byte[] decompress(byte[] compressed) throws CompressionException {
        try {
            if (compressed == null || compressed.length < 1) {
                return new byte[0];
            }

            // Magic byte check
            if (compressed[0] != getMagicByte()) {
                throw new CompressionException("Invalid magic byte for Zstd");
            }
            
            // Try to get original size from Zstd frame (optional)
            long originalSize = Zstd.decompressedSize(compressed, 1, compressed.length - 1);
            
            // Handle unknown size
            if (originalSize <= 0) { 
                // Fallback: Allocate max chunk size (1MB + margin)
                // Most Minecraft chunks are < 100KB, but can go up to 1MB.
                originalSize = 1024 * 1024 + 8192; 
            }
            
            if (originalSize > Integer.MAX_VALUE) {
                 throw new CompressionException("Decompressed size too large for byte array");
            }
            
            byte[] output = new byte[(int)originalSize];
            long size = Zstd.decompressByteArray(output, 0, output.length, compressed, 1, compressed.length - 1);
            
            if (Zstd.isError(size)) {
                throw new CompressionException("Zstd decompression error: " + Zstd.getErrorName(size));
            }
            
            // If we allocated a fallback buffer, strict resize to actual data
            if (output.length != size) {
                byte[] trimmed = new byte[(int)size];
                System.arraycopy(output, 0, trimmed, 0, (int)size);
                return trimmed;
            }
            
            return output;
        } catch (Exception e) {
            throw new CompressionException("Zstd decompression failed", e);
        }
    }

    @Override
    public byte getMagicByte() {
        return 0x54; // 'T' for Turbo (Zstd slot) - Check LRFConstants collision
    }
    
    @Override
    public int getCompressionLevel() {
        return level;
    }
    
    @Override
    public String getName() {
        return "ZSTD";
    }
}
