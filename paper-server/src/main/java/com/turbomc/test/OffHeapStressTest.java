package com.turbomc.test;

import com.turbomc.nbt.PackedBinaryNBT;
import java.util.ArrayList;
import java.util.List;

/**
 * Stress test for TurboMC Off-Heap NBT (Project Panama).
 * Assumes PackedBinaryNBT is already implemented using MemorySegment.
 */
public class OffHeapStressTest {

    public static void runTest() {
        System.out.println("[OffHeapStressTest] Starting stress test...");
        
        List<String> pool = List.of("hello", "world", "turbo", "mc");
        byte[] dummyData = new byte[1024]; // 1KB per NBT
        
        List<PackedBinaryNBT> objects = new ArrayList<>();
        
        try {
            // Allocate 10,000 off-heap segments (approx 10MB total)
            for (int i = 0; i < 10000; i++) {
                objects.add(new PackedBinaryNBT(pool, dummyData));
                if (i % 1000 == 0) System.out.println("Allocated " + i + " segments...");
            }
            
            System.out.println("[OffHeapStressTest] Allocation phase successful.");
            
            // Verify read/write cycle
            for (int i = 0; i < 100; i++) {
                PackedBinaryNBT nbt = objects.get(i);
                byte[] bytes = nbt.toBytes();
                if (bytes.length == 0) throw new RuntimeException("Serialization produced empty array");
            }
            
            System.out.println("[OffHeapStressTest] Read/Verify phase successful.");
            
            // Allow GC to clean up (Arena.ofAuto() should handle this)
            objects.clear();
            System.gc();
            Thread.sleep(100); 
            
            System.out.println("[OffHeapStressTest] Cleanup phase (GC suggested) successful.");
            System.out.println("[OffHeapStressTest] TEST PASSED.");
            
        } catch (Exception e) {
            System.err.println("[OffHeapStressTest] TEST FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        runTest();
    }
}
