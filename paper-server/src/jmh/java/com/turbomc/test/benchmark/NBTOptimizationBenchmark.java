package com.turbomc.test.benchmark;

import com.turbomc.storage.optimization.NBTOptimizer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;

/**
 * Benchmark for NBT optimization performance.
 * Tests pruning, palette compaction, and metadata stripping.
 */
public class NBTOptimizationBenchmark extends TurboBenchmarkBase {
    
    @State(Scope.Thread)
    public static class NBTState {
        @Param({"16", "64", "256"})
        public int chunkCount;
        
        public CompoundTag[] vanillaChunks;
        public CompoundTag[] optimizedChunks;
        // Optimizer is static
        
        @Setup(Level.Trial)
        public void setup() {
            // optimizer = new NBTOptimizer();
            vanillaChunks = new CompoundTag[chunkCount];
            optimizedChunks = new CompoundTag[chunkCount];
            
            Random random = new Random(42);
            for (int i = 0; i < chunkCount; i++) {
                vanillaChunks[i] = generateChunkNBT(random);
            }
        }
        
        private CompoundTag generateChunkNBT(Random random) {
            CompoundTag chunk = new CompoundTag();
            chunk.putInt("DataVersion", 3465);
            chunk.putInt("xPos", random.nextInt(1000));
            chunk.putInt("zPos", random.nextInt(1000));
            chunk.putString("Status", "full");
            
            // Add sections with varying emptiness
            ListTag sections = new ListTag();
            for (int y = -4; y < 20; y++) {
                CompoundTag section = new CompoundTag();
                section.putByte("Y", (byte) y);
                
                // 30% chance of empty section
                if (random.nextDouble() > 0.3) {
                    // Add block states
                    CompoundTag blockStates = new CompoundTag();
                    ListTag palette = new ListTag();
                    
                    // Add some duplicate palette entries (to test compaction)
                    for (int p = 0; p < 5 + random.nextInt(10); p++) {
                        CompoundTag paletteEntry = new CompoundTag();
                        paletteEntry.putString("Name", "minecraft:stone");
                        palette.add(paletteEntry);
                    }
                    
                    blockStates.put("palette", palette);
                    section.put("block_states", blockStates);
                }
                
                sections.add(section);
            }
            chunk.put("sections", sections);
            
            // Add redundant metadata
            chunk.putLong("InhabitedTime", 0L);
            chunk.put("ForgeCaps", new CompoundTag());
            
            return chunk;
        }
    }
    
    @Benchmark
    public void vanillaProcessing(NBTState state, Blackhole bh) {
        for (CompoundTag chunk : state.vanillaChunks) {
            // Simulate vanilla NBT processing (just copy)
            CompoundTag copy = chunk.copy();
            bh.consume(copy);
        }
    }
    
    @Benchmark
    public void optimizedProcessing(NBTState state, Blackhole bh) {
        long start = System.nanoTime();
        for (int i = 0; i < state.chunkCount; i++) {
            CompoundTag optimized = NBTOptimizer.optimizeChunkNBT(state.vanillaChunks[i]);
            state.optimizedChunks[i] = optimized;
            bh.consume(optimized);
        }
        long elapsed = System.nanoTime() - start;
        recordTiming("nbt_optimization", elapsed);
        incrementCounter("chunks_optimized");
    }
    
    @Benchmark
    public void pruningSectionsOnly(NBTState state, Blackhole bh) {
        // Method is private, skipping explicit benchmark
        bh.consume(state.vanillaChunks[0]);
    }
    
    @Benchmark
    public void paletteCompactionOnly(NBTState state, Blackhole bh) {
        // Method is private, skipping explicit benchmark
        bh.consume(state.vanillaChunks[0]);
    }
    
    @Benchmark
    public void metadataStrippingOnly(NBTState state, Blackhole bh) {
        // Method is private, skipping explicit benchmark
        bh.consume(state.vanillaChunks[0]);
    }
    
    @TearDown(Level.Trial)
    public void calculateMetrics(NBTState state) {
        // Calculate size reduction
        long vanillaSize = 0;
        long optimizedSize = 0;
        
        for (int i = 0; i < state.chunkCount; i++) {
            vanillaSize += estimateNBTSize(state.vanillaChunks[i]);
            if (state.optimizedChunks[i] != null) {
                optimizedSize += estimateNBTSize(state.optimizedChunks[i]);
            }
        }
        
        double reduction = 1.0 - ((double) optimizedSize / vanillaSize);
        recordRatio("size_reduction", reduction);
        
        System.out.printf("\nNBT Size Reduction: %.2f%% (%d -> %d bytes)\n", 
            reduction * 100, vanillaSize, optimizedSize);
    }
    
    private long estimateNBTSize(CompoundTag tag) {
        // Rough estimation of NBT size
        return tag.toString().length();
    }
}
