package com.turbomc.test.benchmark;

import com.turbomc.storage.optimization.NBTOptimizer;
import com.turbomc.compression.LZ4CompressorImpl;
import com.turbomc.compression.ZstdCompressor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * COMBINATION BENCHMARK: NBT Optimization + Compression
 * 
 * Tests how NBT optimization affects compression performance.
 * Measures:
 * - Does NBT optimization improve compression ratio?
 * - Does it reduce compression time?
 * - What's the total pipeline overhead?
 */
public class NBTCompressionCombinationBenchmark extends TurboBenchmarkBase {
    
    @State(Scope.Thread)
    public static class CombinationState {
        @Param({"64", "256"})
        public int chunkCount;
        
        public CompoundTag[] vanillaChunks;
        public CompoundTag[] optimizedChunks;
        public byte[][] vanillaBytes;
        public byte[][] optimizedBytes;
        
        // Optimizer is static
        public LZ4CompressorImpl lz4;
        public ZstdCompressor zstd;
        
        @Setup(Level.Trial)
        public void setup() throws IOException {
            // nbtOptimizer = new NBTOptimizer();
            lz4 = new LZ4CompressorImpl(1);
            zstd = new ZstdCompressor(3);
            
            vanillaChunks = new CompoundTag[chunkCount];
            optimizedChunks = new CompoundTag[chunkCount];
            vanillaBytes = new byte[chunkCount][];
            optimizedBytes = new byte[chunkCount][];
            
            Random random = new Random(42);
            for (int i = 0; i < chunkCount; i++) {
                vanillaChunks[i] = generateChunkNBT(random);
                optimizedChunks[i] = NBTOptimizer.optimizeChunkNBT(vanillaChunks[i]);
                
                // Serialize to bytes
                try {
                vanillaBytes[i] = serializeNBT(vanillaChunks[i]);
                optimizedBytes[i] = serializeNBT(optimizedChunks[i]);
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        }
        
        private CompoundTag generateChunkNBT(Random random) {
            CompoundTag chunk = new CompoundTag();
            chunk.putInt("DataVersion", 3465);
            chunk.putInt("xPos", random.nextInt(1000));
            chunk.putInt("zPos", random.nextInt(1000));
            
            ListTag sections = new ListTag();
            for (int y = -4; y < 20; y++) {
                CompoundTag section = new CompoundTag();
                section.putByte("Y", (byte) y);
                
                if (random.nextDouble() > 0.3) {
                    CompoundTag blockStates = new CompoundTag();
                    ListTag palette = new ListTag();
                    for (int p = 0; p < 10; p++) {
                        CompoundTag entry = new CompoundTag();
                        entry.putString("Name", "minecraft:stone");
                        palette.add(entry);
                    }
                    blockStates.put("palette", palette);
                    section.put("block_states", blockStates);
                }
                sections.add(section);
            }
            chunk.put("sections", sections);
            chunk.putLong("InhabitedTime", 0L);
            
            return chunk;
        }
        
        private byte[] serializeNBT(CompoundTag tag) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, baos);
            return baos.toByteArray();
        }
        
        @TearDown(Level.Trial)
        public void cleanup() {

        }
    }
    
    // ========== BASELINE: Vanilla NBT + Compression ==========
    
    @Benchmark
    public void vanillaNBT_LZ4(CombinationState state, Blackhole bh) throws Exception {
        long totalNBT = 0, totalCompress = 0;
        long totalSize = 0, totalCompressed = 0;
        
        for (int i = 0; i < state.chunkCount; i++) {
            long nbtStart = System.nanoTime();
            byte[] nbtBytes = state.vanillaBytes[i];
            totalNBT += System.nanoTime() - nbtStart;
            totalSize += nbtBytes.length;
            
            long compressStart = System.nanoTime();
            byte[] compressed = state.lz4.compress(nbtBytes);
            totalCompress += System.nanoTime() - compressStart;
            totalCompressed += compressed.length;
            
            bh.consume(compressed);
        }
        
        recordTiming("vanilla_nbt_time", totalNBT);
        recordTiming("vanilla_lz4_time", totalCompress);
        recordRatio("vanilla_lz4_ratio", (double) totalCompressed / totalSize);
    }
    
    @Benchmark
    public void vanillaNBT_Zstd(CombinationState state, Blackhole bh) throws Exception {
        long totalCompress = 0;
        long totalSize = 0, totalCompressed = 0;
        
        for (int i = 0; i < state.chunkCount; i++) {
            byte[] nbtBytes = state.vanillaBytes[i];
            totalSize += nbtBytes.length;
            
            long compressStart = System.nanoTime();
            byte[] compressed = state.zstd.compress(nbtBytes);
            totalCompress += System.nanoTime() - compressStart;
            totalCompressed += compressed.length;
            
            bh.consume(compressed);
        }
        
        recordTiming("vanilla_zstd_time", totalCompress);
        recordRatio("vanilla_zstd_ratio", (double) totalCompressed / totalSize);
    }
    
    // ========== OPTIMIZED: NBT Optimization + Compression ==========
    
    @Benchmark
    public void optimizedNBT_LZ4(CombinationState state, Blackhole bh) throws Exception {
        long totalNBT = 0, totalCompress = 0;
        long totalSize = 0, totalCompressed = 0;
        
        for (int i = 0; i < state.chunkCount; i++) {
            long nbtStart = System.nanoTime();
            byte[] nbtBytes = state.optimizedBytes[i];
            totalNBT += System.nanoTime() - nbtStart;
            totalSize += nbtBytes.length;
            
            long compressStart = System.nanoTime();
            byte[] compressed = state.lz4.compress(nbtBytes);
            totalCompress += System.nanoTime() - compressStart;
            totalCompressed += compressed.length;
            
            bh.consume(compressed);
        }
        
        recordTiming("optimized_nbt_time", totalNBT);
        recordTiming("optimized_lz4_time", totalCompress);
        recordRatio("optimized_lz4_ratio", (double) totalCompressed / totalSize);
    }
    
    @Benchmark
    public void optimizedNBT_Zstd(CombinationState state, Blackhole bh) throws Exception {
        long totalCompress = 0;
        long totalSize = 0, totalCompressed = 0;
        
        for (int i = 0; i < state.chunkCount; i++) {
            byte[] nbtBytes = state.optimizedBytes[i];
            totalSize += nbtBytes.length;
            
            long compressStart = System.nanoTime();
            byte[] compressed = state.zstd.compress(nbtBytes);
            totalCompress += System.nanoTime() - compressStart;
            totalCompressed += compressed.length;
            
            bh.consume(compressed);
        }
        
        recordTiming("optimized_zstd_time", totalCompress);
        recordRatio("optimized_zstd_ratio", (double) totalCompressed / totalSize);
    }
    
    // ========== FULL PIPELINE: NBT Optimization → Compression ==========
    
    @Benchmark
    public void fullPipeline_LZ4(CombinationState state, Blackhole bh) throws Exception {
        long totalOptimize = 0, totalSerialize = 0, totalCompress = 0;
        
        for (int i = 0; i < state.chunkCount; i++) {
            // Step 1: NBT Optimization
            long optimizeStart = System.nanoTime();
            CompoundTag optimized = NBTOptimizer.optimizeChunkNBT(state.vanillaChunks[i]);
            totalOptimize += System.nanoTime() - optimizeStart;
            
            // Step 2: Serialization
            long serializeStart = System.nanoTime();
            byte[] nbtBytes = state.optimizedBytes[i]; // Pre-serialized for fairness
            totalSerialize += System.nanoTime() - serializeStart;
            
            // Step 3: Compression
            long compressStart = System.nanoTime();
            byte[] compressed = state.lz4.compress(nbtBytes);
            totalCompress += System.nanoTime() - compressStart;
            
            bh.consume(compressed);
        }
        
        recordTiming("pipeline_optimize", totalOptimize);
        recordTiming("pipeline_serialize", totalSerialize);
        recordTiming("pipeline_compress", totalCompress);
        
        long totalPipeline = totalOptimize + totalSerialize + totalCompress;
        recordTiming("pipeline_total", totalPipeline);
    }
    
    @TearDown(Level.Trial)
    public void analyzeInteraction(CombinationState state) {
        System.out.println("\n=== NBT + Compression Interaction Analysis ===");
        
        // Calculate improvements
        Long vanillaLZ4 = timings.get("vanilla_lz4_time");
        Long optimizedLZ4 = timings.get("optimized_lz4_time");
        
        if (vanillaLZ4 != null && optimizedLZ4 != null) {
            double speedup = (double) vanillaLZ4 / optimizedLZ4;
            System.out.printf("LZ4 Compression Speedup: %.2fx\n", speedup);
        }
        
        // Compression ratio comparison
        Double vanillaRatio = ratios.get("vanilla_lz4_ratio");
        Double optimizedRatio = ratios.get("optimized_lz4_ratio");
        
        if (vanillaRatio != null && optimizedRatio != null) {
            double improvement = (vanillaRatio - optimizedRatio) / vanillaRatio;
            System.out.printf("Compression Ratio Improvement: %.2f%%\n", improvement * 100);
        }
        
        System.out.println("===========================================\n");
    }
}
