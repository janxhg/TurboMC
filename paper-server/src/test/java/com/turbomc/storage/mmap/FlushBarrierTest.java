package com.turbomc.storage.mmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class FlushBarrierTest {

    private FlushBarrier barrier;
    private final Path testPath = Paths.get("test.lrf");

    @BeforeEach
    void setUp() {
        barrier = new FlushBarrier(true);
    }

    @Test
    void testConcurrentReads() throws InterruptedException {
        System.out.println("=== FLUSH BARRIER CONCURRENT READS TEST ===");
        
        CountDownLatch latch = new CountDownLatch(2);
        AtomicBoolean success = new AtomicBoolean(true);

        Runnable reader = () -> {
            barrier.beforeRead();
            try {
                latch.countDown();
                // Hold the lock for a bit to ensure concurrency
                Thread.sleep(100);
            } catch (InterruptedException e) {
                success.set(false);
            } finally {
                barrier.afterRead(null);
            }
        };

        Thread t1 = new Thread(reader);
        Thread t2 = new Thread(reader);

        t1.start();
        t2.start();

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Both readers should acquire lock concurrently");
        assertTrue(success.get());
        
        System.out.println("✓ Concurrent reads verified");
    }

    @Test
    void testFlushBlocksReaders() throws InterruptedException {
        System.out.println("=== FLUSH BARRIER WRITE EXCLUSIVITY TEST ===");
        
        barrier.beforeFlush(testPath); // Acquire write lock
        
        AtomicBoolean readerAcquired = new AtomicBoolean(false);
        Thread readerThread = new Thread(() -> {
            barrier.beforeRead();
            readerAcquired.set(true);
            barrier.afterRead(null);
        });

        readerThread.start();
        
        // Reader should be blocked
        Thread.sleep(100);
        assertFalse(readerAcquired.get(), "Reader should be blocked by active flush");
        
        barrier.afterFlush(testPath, null); // Release write lock
        
        // Now reader should be able to proceed
        readerThread.join(1000);
        assertTrue(readerAcquired.get(), "Reader should acquire lock after flush completes");
        
        System.out.println("✓ Flush exclusivity verified");
    }

    @Test
    void testReaderBlocksFlush() throws InterruptedException {
        System.out.println("=== FLUSH BARRIER READ PRIORITIZATION TEST ===");
        
        barrier.beforeRead(); // Acquire read lock
        
        AtomicBoolean flushAcquired = new AtomicBoolean(false);
        Thread flushThread = new Thread(() -> {
            barrier.beforeFlush(testPath);
            flushAcquired.set(true);
            barrier.afterFlush(testPath, null);
        });

        flushThread.start();
        
        // Flush should be blocked
        Thread.sleep(100);
        assertFalse(flushAcquired.get(), "Flush should be blocked by active reader");
        
        barrier.afterRead(null); // Release read lock
        
        // Now flush should be able to proceed
        flushThread.join(1000);
        assertTrue(flushAcquired.get(), "Flush should acquire lock after reader finishes");
        
        System.out.println("✓ Reader blocking flush verified");
    }

    @Test
    void testStats() {
        System.out.println("=== FLUSH BARRIER STATS TEST ===");
        
        barrier.beforeRead();
        barrier.beforeRead();
        
        FlushBarrier.BarrierStats stats = barrier.getStats();
        assertEquals(2, stats.activeReads());
        assertFalse(stats.writeLocked());
        
        barrier.afterRead(null);
        barrier.afterRead(null);
        
        barrier.beforeFlush(testPath);
        stats = barrier.getStats();
        assertTrue(stats.writeLocked());
        assertEquals(0, stats.activeReads());
        
        barrier.afterFlush(testPath, null);
        
        System.out.println("✓ Stats verified");
    }
}
