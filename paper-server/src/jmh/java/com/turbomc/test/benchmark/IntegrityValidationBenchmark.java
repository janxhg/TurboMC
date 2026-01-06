package com.turbomc.test.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

/**
 * Benchmark for Chunk Integrity Validation.
 * Measures the overhead of calculating checksums (CRC32, etc.) 
 * which ensures data consistency but adds CPU cost.
 */
public class IntegrityValidationBenchmark extends TurboBenchmarkBase {

    @State(Scope.Thread)
    public static class IntegrityState {
        @Param({"4096", "16384", "65536"}) // Chunk sizes
        public int dataSize;

        public byte[] data;
        public CRC32 crc32;

        @Setup(Level.Trial)
        public void setup() {
            data = new byte[dataSize];
            new Random(42).nextBytes(data);
            crc32 = new CRC32();
        }
    }

    @Benchmark
    public void validationCRC32(IntegrityState state, Blackhole bh) {
        state.crc32.reset();
        state.crc32.update(state.data);
        bh.consume(state.crc32.getValue());
    }

    @Benchmark
    public void validationAdler32(IntegrityState state, Blackhole bh) {
        // Comparison with Adler32 (often faster but less robust)
        java.util.zip.Adler32 adler = new java.util.zip.Adler32();
        adler.update(state.data);
        bh.consume(adler.getValue());
    }

    // Simulate "Smart Validation" - sampling only a portion of the data
    @Benchmark
    public void validationSmartSampling(IntegrityState state, Blackhole bh) {
        state.crc32.reset();
        // Validate header (first 128 bytes)
        state.crc32.update(state.data, 0, Math.min(128, state.data.length));
        // Validate footer (last 128 bytes)
        int start = Math.max(0, state.data.length - 128);
        state.crc32.update(state.data, start, state.data.length - start);
        // Sample middle
        int mid = state.data.length / 2;
        state.crc32.update(state.data, mid, Math.min(128, state.data.length - mid));
        
        bh.consume(state.crc32.getValue());
    }
}
