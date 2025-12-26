package com.turbomc.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import com.turbomc.config.TurboConfig;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * Parallel chunk generator for high-speed flight optimization.
 * Generates multiple chunks simultaneously to eliminate lag in unexplored areas.
 * 
 * @author TurboMC
 * @version 2.2.0
 */
public class ParallelChunkGenerator {
    
    private final ServerLevel world;
    private ServerChunkCache chunkCache;
    private final ExecutorService generatorExecutor;
    private final PriorityBlockingQueue<GenerationTask> taskQueue;
    private final ConcurrentHashMap<Long, CompletableFuture<ChunkAccess>> pendingGenerations;
    
    // Configuration
    private final boolean enabled;
    private final int maxConcurrentGenerations;
    private final String priorityMode;
    private final int pregenerationDistance;
    private final boolean smartPredetection;
    
    // Statistics
    private final AtomicInteger chunksGenerated = new AtomicInteger(0);
    private final AtomicInteger chunksPregenerated = new AtomicInteger(0);
    private final AtomicLong totalGenerationTime = new AtomicLong(0);
    private final long startTime;
    
    public ParallelChunkGenerator(ServerLevel world, TurboConfig config) {
        this.world = world;
        // Moved to lazy getter to avoid NPE during world initialization
        this.startTime = System.currentTimeMillis();
        
        // Load configuration
        this.enabled = config.getBoolean("world.generation.parallel-enabled", true);
        int threads = config.getInt("world.generation.generation-threads", 0);
        if (threads <= 0) {
            threads = com.turbomc.core.autopilot.TurboAutopilot.getInstance().getIdealGenerationThreads();
        }
        this.maxConcurrentGenerations = config.getInt("world.generation.max-concurrent-generations", 16);
        this.priorityMode = config.getString("world.generation.priority-mode", "direction");
        this.pregenerationDistance = config.getInt("world.generation.pregeneration-distance", 24);
        this.smartPredetection = config.getBoolean("world.generation.smart-predetection", true);
        
        // Initialize executor
        this.generatorExecutor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "TurboMC-ParallelGen-" + System.currentTimeMillis());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1); // Slightly lower priority
            return t;
        });
        
        this.taskQueue = new PriorityBlockingQueue<>(256);
        this.pendingGenerations = new ConcurrentHashMap<>();
        
        System.out.println("[TurboMC][ParallelGen] Initialized with " + threads + " worker threads");
        System.out.println("[TurboMC][ParallelGen] Max concurrent: " + maxConcurrentGenerations + 
                         ", Priority: " + priorityMode + ", Distance: " + pregenerationDistance);
    }
    
    /**
     * Queue a chunk for parallel generation.
     * Returns immediately with a CompletableFuture.
     */
    public CompletableFuture<ChunkAccess> queueGeneration(int chunkX, int chunkZ, int priority) {
        if (!enabled) {
            return CompletableFuture.completedFuture(null);
        }
        
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        
        // Check if already generating
        CompletableFuture<ChunkAccess> existing = pendingGenerations.get(chunkKey);
        if (existing != null) {
            return existing;
        }
        
        // Create new generation task
        CompletableFuture<ChunkAccess> future = new CompletableFuture<>();
        pendingGenerations.put(chunkKey, future);
        
        GenerationTask task = new GenerationTask(chunkX, chunkZ, priority, future);
        taskQueue.offer(task);
        
        // Submit to executor if below max concurrent
        if (pendingGenerations.size() <= maxConcurrentGenerations) {
            generatorExecutor.submit(this::processNextTask);
        }
        
        return future;
    }
    
    /**
     * Internal helper to get chunk cache safely.
     */
    private ServerChunkCache getChunkCache() {
        if (chunkCache == null) {
            chunkCache = world.getChunkSource();
        }
        return chunkCache;
    }

    /**
     * Process next generation task from queue.
     */
    private void processNextTask() {
        // Load-aware throttling (v2.3.3)
        var health = com.turbomc.core.autopilot.HealthMonitor.getInstance().getLastSnapshot();
        if (health.isCritical()) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {} // Slow down
        }

        GenerationTask task = taskQueue.poll();
        if (task == null) {
            return;
        }
        
        long startGen = System.nanoTime();
        try {
            // Generate chunk using vanilla system
            ServerChunkCache cache = getChunkCache();
            if (cache == null) {
                // Try again later
                taskQueue.offer(task);
                return;
            }
            ChunkAccess chunk = cache.getChunk(task.chunkX, task.chunkZ, ChunkStatus.FULL, true);
            
            long duration = (System.nanoTime() - startGen) / 1_000_000; // ms
            totalGenerationTime.addAndGet(duration);
            int generated = chunksGenerated.incrementAndGet();
            
            // Periodic progress logging (v2.3.3)
            if (generated % 100 == 0) {
                System.out.println("[TurboMC][ParallelGen] Generated " + generated + " chunks for world " + world.dimension().location().getPath() + 
                                 " (Avg: " + String.format("%.1f", (double)totalGenerationTime.get() / generated) + "ms/chunk)");
            }
            
            task.future.complete(chunk);
        } catch (Exception e) {
            System.err.println("[TurboMC][ParallelGen] Failed to generate chunk [" + 
                             task.chunkX + ", " + task.chunkZ + "]: " + e.getMessage());
            task.future.completeExceptionally(e);
        } finally {
            long chunkKey = ChunkPos.asLong(task.chunkX, task.chunkZ);
            pendingGenerations.remove(chunkKey);
        }
    }
    
    /**
     * Pre-generate chunks in player's direction.
     */
    public void pregenerateAhead(int playerChunkX, int playerChunkZ, double velocityX, double velocityZ) {
        if (!enabled || !smartPredetection) {
            return;
        }
        
        // Calculate direction vector
        double dist = Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
        if (dist < 0.1) return; // Not moving
        
        double dirX = velocityX / dist;
        double dirZ = velocityZ / dist;
        
        List<ChunkPos> toGenerate = new ArrayList<>();
        
        // Calculate chunks in flight path
        for (int distance = 4; distance <= pregenerationDistance; distance += 2) {
            int targetX = playerChunkX + (int)(dirX * distance);
            int targetZ = playerChunkZ + (int)(dirZ * distance);
            
            // Add main path chunk
            if (!chunkExistsOnDisk(targetX, targetZ)) {
                toGenerate.add(new ChunkPos(targetX, targetZ));
            }
            
            // Add side chunks (3-wide corridor)
            if (priorityMode.equals("direction")) {
                for (int offset = -1; offset <= 1; offset++) {
                    int sideX = targetX + (int)(-dirZ * offset);
                    int sideZ = targetZ + (int)(dirX * offset);
                    if (!chunkExistsOnDisk(sideX, sideZ)) {
                        toGenerate.add(new ChunkPos(sideX, sideZ));
                    }
                }
            }
        }
        
        // Queue for generation (low priority)
        for (ChunkPos pos : toGenerate) {
            queueGeneration(pos.x, pos.z, 5);
            chunksPregenerated.incrementAndGet();
        }
    }
    
    /**
     * Check if chunk exists on disk (fast check, no generation).
     */
    private boolean chunkExistsOnDisk(int chunkX, int chunkZ) {
        try {
            // This is a fast check that doesn't trigger generation
            ServerChunkCache cache = getChunkCache();
            return cache != null && cache.hasChunk(chunkX, chunkZ);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get statistics for monitoring.
     */
    public GeneratorStats getStats() {
        long elapsed = System.currentTimeMillis() - startTime;
        int generated = chunksGenerated.get();
        int pregenerated = chunksPregenerated.get();
        long totalTime = totalGenerationTime.get();
        double avgTime = generated > 0 ? (double) totalTime / generated : 0;
        
        return new GeneratorStats(
            generated, pregenerated, totalTime, avgTime, 
            pendingGenerations.size(), taskQueue.size(), elapsed
        );
    }
    
    /**
     * Shutdown generator.
     */
    public void shutdown() {
        System.out.println("[TurboMC][ParallelGen] Shutting down... Stats: " + getStats());
        generatorExecutor.shutdown();
        try {
            if (!generatorExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                generatorExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            generatorExecutor.shutdownNow();
        }
    }
    
    /**
     * Generation task with priority.
     */
    private static class GenerationTask implements Comparable<GenerationTask> {
        final int chunkX;
        final int chunkZ;
        final int priority; // Lower = higher priority
        final CompletableFuture<ChunkAccess> future;
        final long timestamp;
        
        GenerationTask(int chunkX, int chunkZ, int priority, CompletableFuture<ChunkAccess> future) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.priority = priority;
            this.future = future;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public int compareTo(GenerationTask other) {
            // Lower priority value = higher priority
            int prioCmp = Integer.compare(this.priority, other.priority);
            if (prioCmp != 0) return prioCmp;
            // If same priority, older tasks first
            return Long.compare(this.timestamp, other.timestamp);
        }
    }
    
    /**
     * Statistics container.
     */
    public static class GeneratorStats {
        public final int chunksGenerated;
        public final int chunksPregenerated;
        public final long totalGenerationTime;
        public final double avgGenerationTime;
        public final int pendingTasks;
        public final int queuedTasks;
        public final long uptimeMs;
        
        GeneratorStats(int chunksGenerated, int chunksPregenerated, long totalGenerationTime, 
                      double avgGenerationTime, int pendingTasks, int queuedTasks, long uptimeMs) {
            this.chunksGenerated = chunksGenerated;
            this.chunksPregenerated = chunksPregenerated;
            this.totalGenerationTime = totalGenerationTime;
            this.avgGenerationTime = avgGenerationTime;
            this.pendingTasks = pendingTasks;
            this.queuedTasks = queuedTasks;
            this.uptimeMs = uptimeMs;
        }
        
        @Override
        public String toString() {
            return String.format("ParallelGen{generated=%d, pregenerated=%d, avgTime=%.1fms, pending=%d, queued=%d, uptime=%ds}",
                chunksGenerated, chunksPregenerated, avgGenerationTime, pendingTasks, queuedTasks, uptimeMs / 1000);
        }
    }
}
