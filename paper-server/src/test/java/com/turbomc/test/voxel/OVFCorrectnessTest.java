package com.turbomc.test.voxel;

import com.turbomc.voxel.ovf.OVFFormat;
import com.turbomc.voxel.ovf.OVFReader;
import com.turbomc.voxel.ovf.OVFWriter;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class OVFCorrectnessTest {

    @Test
    public void testRLECompressionIntegrity() throws Exception {
        int width = 32;
        int height = 32;
        int length = 32;
        int size = width * height * length;

        // 1. Generate Random Data with Runs
        short[] originalData = new short[size];
        List<String> palette = new ArrayList<>();
        palette.add("minecraft:air");
        palette.add("minecraft:stone");
        palette.add("minecraft:dirt");
        palette.add("minecraft:grass");

        Random rand = new Random(12345);
        int idx = 0;
        while (idx < size) {
            short val = (short) rand.nextInt(palette.size());
            int runLength = 1 + rand.nextInt(50); // Random runs
            for (int i = 0; i < runLength && idx < size; i++) {
                originalData[idx++] = val;
            }
        }

        // 2. Write to OVF
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OVFWriter.write(baos, width, height, length, palette, originalData);
        byte[] compressedBytes = baos.toByteArray();

        System.out.println("OVF Size for 32^3 grid: " + compressedBytes.length + " bytes");
        // Check compression ratio? 32*32*32 * 2 = 65KB raw (short array).
        // Let's see output.

        // 3. Read from OVF
        ByteArrayInputStream bais = new ByteArrayInputStream(compressedBytes);
        OVFReader.OVFContainer container = OVFReader.read(bais);

        // 4. Verification
        assertEquals(width, container.header.width);
        assertEquals(height, container.header.height);
        assertEquals(length, container.header.length);
        assertEquals(palette.size(), container.palette.size());
        assertEquals(palette.get(1), container.palette.get(1));
        
        assertArrayEquals(originalData, container.blockData, "Block data mismatch!");
    }
    
    @Test
    public void testMassiveCompression() throws Exception {
        // Test a giant empty cube (air)
        int dim = 256;
        int size = dim*dim*dim;
        short[] data = new short[size]; // All 0 (air)
        List<String> pal = List.of("minecraft:air");
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long start = System.nanoTime();
        OVFWriter.write(baos, dim, dim, dim, pal, data);
        long time = System.nanoTime() - start;
        byte[] bytes = baos.toByteArray();
        
        System.out.println("OVF Compression Time for 16M blocks: " + (time / 1_000_000.0) + "ms");
        System.out.println("OVF Size: " + bytes.length + " bytes");
        
        // Should be extremely small. 
        // 16M blocks. 
        // Run max size = 2GB (int).
        // My format: 0xFF + 4-byte Int.
        // It should take ~ 7 bytes per Max-Int run (2B items).
        // So ~32 bytes total.
        
        assertTrue(bytes.length < 1000, "Compressed size should be tiny for uniform data");
    }
}
