package com.turbomc.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.text.DecimalFormat;

/**
 * Comprehensive benchmark suite for LRF vs MCA performance.
 * Provides detailed performance metrics and comparisons.
 * 
 * Run with: ./gradlew test --tests "*LRFBenchmarkSuite*"
 */
public class LRFBenchmarkSuite {
    
    private static final DecimalFormat DF = new DecimalFormat("#.##");
    private Path testDir;
    private Random random;
    
    @BeforeEach
    void setUp() throws IOException {
        testDir = Files.createTempDirectory("turbomc_benchmark");
        random = new Random(99999); // Fixed seed for reproducible benchmarks
    }
    
    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(testDir)) {
            Files.walk(testDir)
                 .sorted((a, b) -> b.compareTo(a))
                 .forEach(path -> {
                     try {
                         Files.deleteIfExists(path);
                     } catch (IOException e) {
                         // Ignore cleanup errors
                     }
                 });
        }
    }
    
    @Test
    @Disabled("Run manually for comprehensive benchmarks")
    void comprehensivePerformanceBenchmark() throws IOException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TURBOMC LRF COMPREHENSIVE PERFORMANCE BENCHMARK");
        System.out.println("=".repeat(80));
        
        // Test different data sizes
        int[] dataSizes = {1, 10, 50, 100, 500, 1000}; // MB
        int[] chunkCounts = {100, 1000, 5000, 10000};
        
        System.out.println("\n--- DATA SIZE BENCHMARKS ---");
        for (int sizeMB : dataSizes) {
            benchmarkDataSize(sizeMB);
        }
        
        System.out.println("\n--- CHUNK COUNT BENCHMARKS ---");
        for (int chunks : chunkCounts) {
            benchmarkChunkCount(chunks);
        }
        
        System.out.println("\n--- REAL-WORLD SCENARIOS ---");
        benchmarkRealWorldScenarios();
        
        System.out.println("\n--- MEMORY EFFICIENCY ---");
        benchmarkMemoryEfficiency();
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("BENCHMARK COMPLETE");
        System.out.println("=".repeat(80));
    }
    
    private void benchmarkDataSize(int sizeMB) throws IOException {
        System.out.printf("\nTesting %d MB data set:%n", sizeMB);
        
        byte[] testData = new byte[sizeMB * 1024 * 1024];
        random.nextBytes(testData);
        
        // Benchmark MCA (ZLIB)
        long mcaStartTime = System.nanoTime();
        byte[] mcaCompressed = compressZLIB(testData);
        long mcaCompressTime = System.nanoTime() - mcaStartTime;
        
        long mcaDecompressStartTime = System.nanoTime();
        decompressZLIB(mcaCompressed);
        long mcaDecompressTime = System.nanoTime() - mcaDecompressStartTime;
        
        // Benchmark LRF (LZ4)
        long lrfStartTime = System.nanoTime();
        byte[] lrfCompressed = compressLZ4(testData);
        long lrfCompressTime = System.nanoTime() - lrfStartTime;
        
        long lrfDecompressStartTime = System.nanoTime();
        decompressLZ4(lrfCompressed);
        long lrfDecompressTime = System.nanoTime() - lrfDecompressStartTime;
        
        // Calculate metrics
        double mcaCompressSpeed = (sizeMB * 1024.0 * 1024.0) / (mcaCompressTime / 1_000_000_000.0) / (1024 * 1024);
        double lrfCompressSpeed = (sizeMB * 1024.0 * 1024.0) / (lrfCompressTime / 1_000_000_000.0) / (1024 * 1024);
        
        double mcaDecompressSpeed = (sizeMB * 1024.0 * 1024.0) / (mcaDecompressTime / 1_000_000_000.0) / (1024 * 1024);
        double lrfDecompressSpeed = (sizeMB * 1024.0 * 1024.0) / (lrfDecompressTime / 1_000_000_000.0) / (1024 * 1024);
        
        System.out.printf("  MCA: %s MB/s compress, %s MB/s decompress, %s MB size%n",
                         DF.format(mcaCompressSpeed), DF.format(mcaDecompressSpeed),
                         DF.format(mcaCompressed.length / (1024.0 * 1024.0)));
        System.out.printf("  LRF: %s MB/s compress, %s MB/s decompress, %s MB size%n",
                         DF.format(lrfCompressSpeed), DF.format(lrfDecompressSpeed),
                         DF.format(lrfCompressed.length / (1024.0 * 1024.0)));
        
        double compressionImprovement = (double) mcaCompressed.length / lrfCompressed.length;
        double compressSpeedImprovement = lrfCompressSpeed / mcaCompressSpeed;
        double decompressSpeedImprovement = lrfDecompressSpeed / mcaDecompressSpeed;
        
        System.out.printf("  Improvements: %.2fx compression, %.2fx compress speed, %.2fx decompress speed%n",
                         compressionImprovement, compressSpeedImprovement, decompressSpeedImprovement);
    }
    
    private void benchmarkChunkCount(int chunkCount) throws IOException {
        System.out.printf("\nTesting %d chunks (%.1f MB):%n", chunkCount, chunkCount * 0.5);
        
        byte[][] chunks = generateChunks(chunkCount);
        
        // Benchmark MCA processing
        long mcaStartTime = System.nanoTime();
        long mcaTotalSize = 0;
        for (byte[] chunk : chunks) {
            byte[] compressed = compressZLIB(chunk);
            mcaTotalSize += compressed.length;
            decompressZLIB(compressed);
        }
        long mcaTotalTime = System.nanoTime() - mcaStartTime;
        
        // Benchmark LRF processing
        long lrfStartTime = System.nanoTime();
        long lrfTotalSize = 0;
        for (byte[] chunk : chunks) {
            byte[] compressed = compressLZ4(chunk);
            lrfTotalSize += compressed.length;
            decompressLZ4(compressed);
        }
        long lrfTotalTime = System.nanoTime() - lrfStartTime;
        
        double mcaThroughput = (chunkCount * 1000.0) / (mcaTotalTime / 1_000_000.0);
        double lrfThroughput = (chunkCount * 1000.0) / (lrfTotalTime / 1_000_000.0);
        
        System.out.printf("  MCA: %s chunks/sec, %s MB total%n",
                         DF.format(mcaThroughput), DF.format(mcaTotalSize / (1024.0 * 1024.0)));
        System.out.printf("  LRF: %s chunks/sec, %s MB total%n",
                         DF.format(lrfThroughput), DF.format(lrfTotalSize / (1024.0 * 1024.0)));
        
        double throughputImprovement = lrfThroughput / mcaThroughput;
        double spaceImprovement = (double) mcaTotalSize / lrfTotalSize;
        
        System.out.printf("  Improvements: %.2fx throughput, %.2fx space savings%n",
                         throughputImprovement, spaceImprovement);
    }
    
    private void benchmarkRealWorldScenarios() throws IOException {
        System.out.println("\nScenario: Server startup (loading 1000 chunks)");
        
        byte[][] chunks = generateChunks(1000);
        
        // Simulate server startup - sequential loading
        long mcaStartupTime = measureSequentialLoading(chunks, false);
        long lrfStartupTime = measureSequentialLoading(chunks, true);
        
        System.out.printf("  MCA startup time: %s ms%n", DF.format(mcaStartupTime));
        System.out.printf("  LRF startup time: %s ms%n", DF.format(lrfStartupTime));
        System.out.printf("  Startup improvement: %.2fx faster%n", (double) mcaStartupTime / lrfStartupTime);
        
        System.out.println("\nScenario: Player exploration (random chunk access)");
        
        long mcaExplorationTime = measureRandomAccess(chunks, false);
        long lrfExplorationTime = measureRandomAccess(chunks, true);
        
        System.out.printf("  MCA exploration time: %s ms%n", DF.format(mcaExplorationTime));
        System.out.printf("  LRF exploration time: %s ms%n", DF.format(lrfExplorationTime));
        System.out.printf("  Exploration improvement: %.2fx faster%n", (double) mcaExplorationTime / lrfExplorationTime);
        
        System.out.println("\nScenario: World backup (compression)");
        
        long mcaBackupTime = measureCompressionTime(chunks, false);
        long lrfBackupTime = measureCompressionTime(chunks, true);
        
        System.out.printf("  MCA backup time: %s ms%n", DF.format(mcaBackupTime));
        System.out.printf("  LRF backup time: %s ms%n", DF.format(lrfBackupTime));
        System.out.printf("  Backup improvement: %.2fx faster%n", (double) mcaBackupTime / lrfBackupTime);
    }
    
    private void benchmarkMemoryEfficiency() throws IOException {
        System.out.println("\nMemory efficiency comparison (1000 chunks):");
        
        byte[][] chunks = generateChunks(1000);
        
        // Measure memory usage for MCA
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        long mcaMemoryBefore = runtime.totalMemory() - runtime.freeMemory();
        
        List<byte[]> mcaCompressed = new ArrayList<>();
        for (byte[] chunk : chunks) {
            mcaCompressed.add(compressZLIB(chunk));
        }
        
        runtime.gc();
        long mcaMemoryAfter = runtime.totalMemory() - runtime.freeMemory();
        long mcaMemoryUsed = mcaMemoryAfter - mcaMemoryBefore;
        
        // Measure memory usage for LRF
        runtime.gc();
        long lrfMemoryBefore = runtime.totalMemory() - runtime.freeMemory();
        
        List<byte[]> lrfCompressed = new ArrayList<>();
        for (byte[] chunk : chunks) {
            lrfCompressed.add(compressLZ4(chunk));
        }
        
        runtime.gc();
        long lrfMemoryAfter = runtime.totalMemory() - runtime.freeMemory();
        long lrfMemoryUsed = lrfMemoryAfter - lrfMemoryBefore;
        
        System.out.printf("  MCA memory usage: %s MB%n", DF.format(mcaMemoryUsed / (1024.0 * 1024.0)));
        System.out.printf("  LRF memory usage: %s MB%n", DF.format(lrfMemoryUsed / (1024.0 * 1024.0)));
        
        double memoryEfficiency = (double) mcaMemoryUsed / lrfMemoryUsed;
        System.out.printf("  Memory efficiency: %.2fx better%n", memoryEfficiency);
        
        // Clean up
        mcaCompressed.clear();
        lrfCompressed.clear();
        runtime.gc();
    }
    
    @Test
    void quickPerformanceCheck() throws IOException {
        System.out.println("\n--- QUICK PERFORMANCE CHECK ---");
        
        byte[] testData = new byte[10 * 1024 * 1024]; // 10MB
        random.nextBytes(testData);
        
        // Quick compression test
        long mcaTime = measureCompressDecompress(testData, false);
        long lrfTime = measureCompressDecompress(testData, true);
        
        System.out.printf("MCA total time: %s ms%n", DF.format(mcaTime));
        System.out.printf("LRF total time: %s ms%n", DF.format(lrfTime));
        System.out.printf("Performance improvement: %.2fx%n", (double) mcaTime / lrfTime);
        
        // LRF should be faster
        assertTrue(lrfTime < mcaTime, "LRF should be faster than MCA");
    }
    
    // Helper methods
    
    private byte[][] generateChunks(int count) {
        byte[][] chunks = new byte[count][];
        for (int i = 0; i < count; i++) {
            chunks[i] = new byte[512 * 1024]; // 512KB per chunk
            random.nextBytes(chunks[i]);
        }
        return chunks;
    }
    
    private long measureSequentialLoading(byte[][] chunks, boolean useLRF) throws IOException {
        long startTime = System.nanoTime();
        
        for (byte[] chunk : chunks) {
            if (useLRF) {
                byte[] compressed = compressLZ4(chunk);
                decompressLZ4(compressed);
            } else {
                byte[] compressed = compressZLIB(chunk);
                decompressZLIB(compressed);
            }
        }
        
        return (System.nanoTime() - startTime) / 1_000_000; // Convert to ms
    }
    
    private long measureRandomAccess(byte[][] chunks, boolean useLRF) throws IOException {
        Random accessRandom = new Random(12345);
        long startTime = System.nanoTime();
        
        for (int i = 0; i < chunks.length; i++) {
            int index = accessRandom.nextInt(chunks.length);
            byte[] chunk = chunks[index];
            
            if (useLRF) {
                byte[] compressed = compressLZ4(chunk);
                decompressLZ4(compressed);
            } else {
                byte[] compressed = compressZLIB(chunk);
                decompressZLIB(compressed);
            }
        }
        
        return (System.nanoTime() - startTime) / 1_000_000; // Convert to ms
    }
    
    private long measureCompressionTime(byte[][] chunks, boolean useLRF) throws IOException {
        long startTime = System.nanoTime();
        
        for (byte[] chunk : chunks) {
            if (useLRF) {
                compressLZ4(chunk);
            } else {
                compressZLIB(chunk);
            }
        }
        
        return (System.nanoTime() - startTime) / 1_000_000; // Convert to ms
    }
    
    private long measureCompressDecompress(byte[] data, boolean useLRF) throws IOException {
        long startTime = System.nanoTime();
        
        if (useLRF) {
            byte[] compressed = compressLZ4(data);
            decompressLZ4(compressed);
        } else {
            byte[] compressed = compressZLIB(data);
            decompressZLIB(compressed);
        }
        
        return (System.nanoTime() - startTime) / 1_000_000; // Convert to ms
    }
    
    private byte[] compressLZ4(byte[] data) {
        net.jpountz.lz4.LZ4Factory factory = net.jpountz.lz4.LZ4Factory.fastestInstance();
        net.jpountz.lz4.LZ4Compressor compressor = factory.fastCompressor();
        
        int maxCompressedLength = compressor.maxCompressedLength(data.length);
        byte[] compressed = new byte[maxCompressedLength];
        int compressedLength = compressor.compress(data, 0, data.length, compressed, 0, maxCompressedLength);
        
        byte[] result = new byte[compressedLength];
        System.arraycopy(compressed, 0, result, 0, compressedLength);
        return result;
    }
    
    private byte[] compressZLIB(byte[] data) throws IOException {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(6);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.util.zip.DeflaterOutputStream dos = new java.util.zip.DeflaterOutputStream(baos, deflater);
        
        dos.write(data);
        dos.close();
        deflater.end();
        
        return baos.toByteArray();
    }
    
    private void decompressLZ4(byte[] compressedData) {
        net.jpountz.lz4.LZ4Factory factory = net.jpountz.lz4.LZ4Factory.fastestInstance();
        net.jpountz.lz4.LZ4FastDecompressor decompressor = factory.fastDecompressor();
        
        byte[] decompressed = new byte[compressedData.length * 2]; // Overestimate
        decompressor.decompress(compressedData, 0, decompressed, 0, decompressed.length);
    }
    
    private void decompressZLIB(byte[] compressedData) throws IOException {
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(compressedData);
        java.util.zip.InflaterInputStream iis = new java.util.zip.InflaterInputStream(bais, inflater);
        
        byte[] buffer = new byte[8192];
        while (iis.read(buffer) > 0) {
            // Read and discard
        }
        
        iis.close();
        inflater.end();
    }
}
