package com.turbomc.test.benchmark;

import com.turbomc.compression.TurboCompressionService;
import com.turbomc.compression.LZ4CompressorImpl;
import com.turbomc.compression.ZstdCompressor;
import com.turbomc.compression.ZlibCompressor;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;

/**
 * Benchmark for compression algorithm comparison.
 * Tests LZ4, Zstd, and Zlib with different data patterns.
 */
public class CompressionBenchmark extends TurboBenchmarkBase {
    
    @State(Scope.Thread)
    public static class CompressionState {
        @Param({"1024", "4096", "16384", "65536"})
        public int dataSize;
        
        @Param({"RANDOM", "REPETITIVE", "MIXED"})
        public DataPattern pattern;
        
        public byte[] testData;
        public LZ4CompressorImpl lz4;
        public ZstdCompressor zstd;
        public ZlibCompressor zlib;
        
        @Setup(Level.Trial)
        public void setup() {
            lz4 = new LZ4CompressorImpl(1);
            zstd = new ZstdCompressor(3);
            zlib = new ZlibCompressor(6);
            
            testData = generateTestData(dataSize, pattern);
        }
        
        private byte[] generateTestData(int size, DataPattern pattern) {
            byte[] data = new byte[size];
            Random random = new Random(42);
            
            switch (pattern) {
                case RANDOM:
                    random.nextBytes(data);
                    break;
                case REPETITIVE:
                    // Highly compressible data
                    for (int i = 0; i < size; i++) {
                        data[i] = (byte) (i % 16);
                    }
                    break;
                case MIXED:
                    // Mix of random and repetitive
                    for (int i = 0; i < size; i++) {
                        if (i % 4 == 0) {
                            data[i] = (byte) random.nextInt(256);
                        } else {
                            data[i] = (byte) (i % 32);
                        }
                    }
                    break;
            }
            
            return data;
        }
        
        @TearDown(Level.Trial)
        public void cleanup() {

        }
    }
    
    public enum DataPattern {
        RANDOM, REPETITIVE, MIXED
    }
    
    @Benchmark
    public void lz4Compression(CompressionState state, Blackhole bh) throws Exception {
        long start = System.nanoTime();
        byte[] compressed = state.lz4.compress(state.testData);
        long elapsed = System.nanoTime() - start;
        
        recordTiming("lz4_compress", elapsed);
        recordRatio("lz4_ratio", (double) compressed.length / state.testData.length);
        bh.consume(compressed);
    }
    
    @Benchmark
    public void lz4Decompression(CompressionState state, Blackhole bh) throws Exception {
        byte[] compressed = state.lz4.compress(state.testData);
        
        long start = System.nanoTime();
        byte[] decompressed = state.lz4.decompress(compressed);
        long elapsed = System.nanoTime() - start;
        
        recordTiming("lz4_decompress", elapsed);
        bh.consume(decompressed);
    }
    
    @Benchmark
    public void zstdCompression(CompressionState state, Blackhole bh) throws Exception {
        long start = System.nanoTime();
        byte[] compressed = state.zstd.compress(state.testData);
        long elapsed = System.nanoTime() - start;
        
        recordTiming("zstd_compress", elapsed);
        recordRatio("zstd_ratio", (double) compressed.length / state.testData.length);
        bh.consume(compressed);
    }
    
    @Benchmark
    public void zstdDecompression(CompressionState state, Blackhole bh) throws Exception {
        byte[] compressed = state.zstd.compress(state.testData);
        
        long start = System.nanoTime();
        byte[] decompressed = state.zstd.decompress(compressed);
        long elapsed = System.nanoTime() - start;
        
        recordTiming("zstd_decompress", elapsed);
        bh.consume(decompressed);
    }
    
    @Benchmark
    public void zlibCompression(CompressionState state, Blackhole bh) throws Exception {
        long start = System.nanoTime();
        byte[] compressed = state.zlib.compress(state.testData);
        long elapsed = System.nanoTime() - start;
        
        recordTiming("zlib_compress", elapsed);
        recordRatio("zlib_ratio", (double) compressed.length / state.testData.length);
        bh.consume(compressed);
    }
    
    @Benchmark
    public void zlibDecompression(CompressionState state, Blackhole bh) throws Exception {
        byte[] compressed = state.zlib.compress(state.testData);
        
        long start = System.nanoTime();
        byte[] decompressed = state.zlib.decompress(compressed);
        long elapsed = System.nanoTime() - start;
        
        recordTiming("zlib_decompress", elapsed);
        bh.consume(decompressed);
    }
    
    @Benchmark
    public void roundTripLZ4(CompressionState state, Blackhole bh) throws Exception {
        byte[] compressed = state.lz4.compress(state.testData);
        byte[] decompressed = state.lz4.decompress(compressed);
        bh.consume(decompressed);
    }
    
    @Benchmark
    public void roundTripZstd(CompressionState state, Blackhole bh) throws Exception {
        byte[] compressed = state.zstd.compress(state.testData);
        byte[] decompressed = state.zstd.decompress(compressed);
        bh.consume(decompressed);
    }
    
    @Benchmark
    public void roundTripZlib(CompressionState state, Blackhole bh) throws Exception {
        byte[] compressed = state.zlib.compress(state.testData);
        byte[] decompressed = state.zlib.decompress(compressed);
        bh.consume(decompressed);
    }
}
