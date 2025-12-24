package com.turbomc.nbt;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.io.IOException;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress test for PackedBinaryNBT to ensure off-heap memory stability.
 */
public class OffHeapStressTest {

    @Test
    public void runStressTest() throws Exception {
        System.out.println("Starting Off-Heap NBT Stress Test...");
        
        int iterations = 100_000;
        int payloadSize = 1024; // 1KB per payload
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < iterations; i++) {
            runIteration(payloadSize);
            
            if (i % 20_000 == 0) {
                System.out.println("Iteration: " + i + " (Memory: " + 
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "MB)");
            }
        }
        
        long endTime = System.currentTimeMillis();
        System.out.println("Stress test completed in " + (endTime - startTime) + "ms");
    }

    private static void runIteration(int size) throws IOException {
        byte[] data = new byte[size];
        new Random().nextBytes(data);
        
        List<String> pool = new ArrayList<>();
        pool.add("test_key_" + size);
        
        // 1. Create (Allocates Off-Heap)
        PackedBinaryNBT packed = new PackedBinaryNBT(pool, data);
        
        // 2. Access (Verify not corrupted)
        if (packed.getPayload().byteSize() != size) {
            throw new IllegalStateException("Size mismatch!");
        }
        
        // 3. Convert (Test zero-copy view if used in logic, though here we just test creation/GC)
        // MemorySegment should be cleared by Arena.ofAuto() when 'packed' is GC'd
    }
}
