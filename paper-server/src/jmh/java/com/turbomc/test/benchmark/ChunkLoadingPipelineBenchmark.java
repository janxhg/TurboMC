package com.turbomc.test.benchmark;

import com.turbomc.compression.LZ4CompressorImpl;
import com.turbomc.storage.optimization.NBTOptimizer;
import io.netty.buffer.PooledByteBufAllocator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * FULL PIPELINE INTEGRATION TEST
 * 
 * Simulates the entire lifecycle of a chunk save:
 * 1. NBT Optimization (Pruning/stripping)
 * 2. Serialization to Byte Stream
 * 3. Buffer allocation
 * 4. Compression
 * 5. Integrity Check calculation
 * 
 * This identifies the true bottleneck in the chain.
 */
public class ChunkLoadingPipelineBenchmark extends TurboBenchmarkBase {

    @State(Scope.Thread)
    public static class PipelineState {
        @Param({"false", "true"})
        public boolean enableOptimization;

        public CompoundTag rawChunk;
        // NBTOptimizer is static, no instance needed
        public LZ4CompressorImpl compressor;
        public java.util.zip.CRC32 crc32;

        @Setup(Level.Trial)
        public void setup() {
            // optimizer = new NBTOptimizer(); // Static
            compressor = new LZ4CompressorImpl(1);
            crc32 = new java.util.zip.CRC32();
            
            // Generate a complex chunk
            rawChunk = generateComplexChunk();
        }
        
        @TearDown(Level.Trial)
        public void teardown() {

        }

        private CompoundTag generateComplexChunk() {
            CompoundTag chunk = new CompoundTag();
            chunk.putInt("DataVersion", 3465);
            ListTag sections = new ListTag();
            Random r = new Random(12345);
            for (int i = 0; i < 16; i++) {
                CompoundTag section = new CompoundTag();
                section.putByte("Y", (byte)i);
                // Simulate block data with some entropy
                byte[] blocks = new byte[4096];
                r.nextBytes(blocks);
                section.putByteArray("Blocks", blocks);
                sections.add(section);
            }
            chunk.put("sections", sections);
            return chunk;
        }
    }

    @Benchmark
    public void fullSavePipeline(PipelineState state, Blackhole bh) throws IOException {
        CompoundTag tagToProcess = state.rawChunk;

        // 1. OPTIMIZATION PHASE
        if (state.enableOptimization) {
            tagToProcess = NBTOptimizer.optimizeChunkNBT(tagToProcess);
        }

        // 2. SERIALIZATION PHASE
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192); // Basic allocation
        NbtIo.write(tagToProcess, new java.io.DataOutputStream(baos));
        byte[] serialized = baos.toByteArray();

        // 3. COMPRESSION PHASE
        byte[] compressed = null;
        try {
            compressed = state.compressor.compress(serialized);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        // 4. INTEGRITY PHASE
        state.crc32.reset();
        state.crc32.update(compressed);
        long checksum = state.crc32.getValue();

        bh.consume(checksum);
    }
}
