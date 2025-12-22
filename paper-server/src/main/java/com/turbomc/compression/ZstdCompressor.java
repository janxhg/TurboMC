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
        try {
            long maxCompressedSize = Zstd.compressBound(data.length);
            // Verify size fits in array (Java arrays limited to 2GB approx)
            if (maxCompressedSize > Integer.MAX_VALUE) {
                throw new CompressionException("Data too large for single array compression");
            }
            
            byte[] output = new byte[(int) maxCompressedSize + 1]; // +1 for magic byte
            output[0] = getMagicByte();
            
            long compressedSize = Zstd.compressByteArray(output, 1, (int)maxCompressedSize, data, 0, data.length, level);
            
            if (Zstd.isError(compressedSize)) {
                 throw new CompressionException("Zstd compression error: " + Zstd.getErrorName(compressedSize));
            }
            
            // Trim array if needed?
            // Usually we return exact array or let the caller handle it. Assumed caller expects exact array?
            // TurboCompressionService returns byte[], so exact array is safer.
            byte[] exact = new byte[(int)compressedSize + 1];
            System.arraycopy(output, 0, exact, 0, (int)compressedSize + 1);
            return exact;
            
        } catch (Exception e) {
            throw new CompressionException("Zstd compression failed", e);
        }
    }

    @Override
    public byte[] decompress(byte[] compressed) throws CompressionException {
        try {
            // Magic byte check
            if (compressed[0] != getMagicByte()) {
                throw new CompressionException("Invalid magic byte for Zstd");
            }
            
            // We need original size.
            // ZSTD frames *can* store original size, but it's optional?
            // Zstd.decompressedSize(byte[] src, int offset)
            long originalSize = Zstd.decompressedSize(compressed, 1, compressed.length - 1);
            
            // FIXED: Handle unknown size properly
            if (originalSize == 0) { 
                if (compressed.length == 1) return new byte[0]; // Just magic byte
                // Unknown size - need to use streaming decompression
                throw new CompressionException("Zstd frame size unknown - not supported for chunk data");
            }
            
            if (originalSize > Integer.MAX_VALUE) {
                 throw new CompressionException("Decompressed size too large for byte array");
            }
            
            byte[] output = new byte[(int)originalSize];
            long size = Zstd.decompressByteArray(output, 0, output.length, compressed, 1, compressed.length - 1);
            
            if (Zstd.isError(size)) {
                throw new CompressionException("Zstd decompression error: " + Zstd.getErrorName(size));
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
