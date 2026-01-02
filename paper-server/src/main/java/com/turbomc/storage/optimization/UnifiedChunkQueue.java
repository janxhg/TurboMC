package com.turbomc.storage.optimization;

import com.turbomc.config.TurboConfig;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * Unified Chunk Queue - Fase 3: Coordinación Centralizada
 * 
 * Sistema unificado de gestión de chunks que coordina:
 * - ParallelChunkGenerator requests
 * - HyperView prefetching requests  
 * - Storage loading requests
 * - Priority-based scheduling
 * 
 * @author TurboMC
 * @version 2.3.5
 */
public class UnifiedChunkQueue {
    
    // Priority levels: 1 (highest) to 10 (lowest)
    public enum TaskType {
        PLAYER_REQUEST(1),      // Player explicitly requesting chunk
        SPAWN_CHUNK(2),         // Spawn area chunks
        PRIORITY_LOAD(3),       // High priority loading
        PARALLEL_GENERATION(5), // Parallel generation
        HYPERVIEW_PREFETCH(8),  // HyperView prefetching
        BACKGROUND_GENERATION(10); // Background generation
        
        public final int priority;
        TaskType(int priority) { this.priority = priority; }
    }
    
    public static class ChunkTask {
        public final ChunkPos position;
        public final TaskType type;
        public final ServerLevel world;
        public final long timestamp;
        public final String requestId;
        public final CompletableFuture<Boolean> future;
        
        public ChunkTask(ChunkPos position, TaskType type, ServerLevel world, String requestId) {
            this.position = position;
            this.type = type;
            this.world = world;
            this.timestamp = System.currentTimeMillis();
            this.requestId = requestId;
            this.future = new CompletableFuture<>();
        }
    }
    
    // Priority queue with custom comparator
    private final PriorityBlockingQueue<ChunkTask> taskQueue;
    
    // Deduplication and tracking
    private final Map<Long, ChunkTask> activeTasks = new ConcurrentHashMap<>();
    private final Map<String, ChunkTask> requestTasks = new ConcurrentHashMap<>();
    
    // Statistics & Counters (Atomic for O(1) access)
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    private final AtomicInteger totalQueued = new AtomicInteger(0);
    private final AtomicInteger totalCompleted = new AtomicInteger(0);
    private final AtomicInteger totalDeduplicated = new AtomicInteger(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    
    // Configuration
    private int maxQueueSize;
    private int maxConcurrentTasks;
    private boolean enableDeduplication;
    
    // Logger
    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(UnifiedChunkQueue.class.getName());
    
    // Singleton instance
    private static volatile UnifiedChunkQueue instance;
    
    public static UnifiedChunkQueue getInstance() {
        if (instance == null) {
            synchronized (UnifiedChunkQueue.class) {
                if (instance == null) {
                    instance = new UnifiedChunkQueue();
                }
            }
        }
        return instance;
    }
    
    private UnifiedChunkQueue() {
        TurboConfig config = TurboConfig.getInstance();
        
        this.maxQueueSize = config.getInt("storage.unified.max-queue-size", 10000);
        this.maxConcurrentTasks = config.getInt("storage.unified.max-concurrent-tasks", 64);
        this.enableDeduplication = config.getBoolean("storage.unified.enable-deduplication", true);
        
        // Priority queue with custom comparator
        this.taskQueue = new PriorityBlockingQueue<>(Math.max(1, maxQueueSize), 
            Comparator.comparingInt((ChunkTask t) -> t.type.priority)
                     .thenComparingLong(t -> t.timestamp));
                     
        LOGGER.info("[TurboMC][UnifiedQueue] Initialized: maxQueue=" + maxQueueSize + 
                          ", maxConcurrent=" + maxConcurrentTasks + 
                          ", deduplication=" + enableDeduplication);
    }
    
    public void updateSettings(int maxQueue, int maxConcurrent, boolean dedupe) {
        this.maxQueueSize = maxQueue;
        this.maxConcurrentTasks = maxConcurrent;
        this.enableDeduplication = dedupe;
    }

    /**
     * Queue a chunk task with automatic deduplication
     */
    public CompletableFuture<Boolean> queueTask(ChunkPos pos, TaskType type, ServerLevel world, String requestId) {
        if (activeTaskCount.get() >= maxConcurrentTasks) {
            return CompletableFuture.completedFuture(false); // Task limit reached
        }
        
        if (taskQueue.size() >= maxQueueSize) {
            return CompletableFuture.completedFuture(false); // Queue full
        }
        
        long chunkKey = ChunkPos.asLong(pos.x, pos.z);
        
        // Deduplication check
        if (enableDeduplication) {
            ChunkTask[] result = new ChunkTask[1];
            activeTasks.compute(chunkKey, (key, existing) -> {
                if (existing != null) {
                    if (type.priority < existing.type.priority) {
                        requestTasks.remove(existing.requestId);
                        // Do not decrement activeTaskCount here because we are technically replacing one with another
                        // Wait, if we replace, we might remove the old one from queue?
                        // PriorityBlockingQueue.remove is O(N). Avoiding it is better.
                        // We will mark old task cancelled or just let it run?
                        // Current logic in original code just returned null to replace? 
                        // "return null" in compute removes the mapping. 
                        // So we remove existing, then we will add new.
                        totalDeduplicated.incrementAndGet();
                        activeTaskCount.decrementAndGet(); 
                        return null; 
                    }
                    result[0] = existing;
                    return existing;
                }
                return null;
            });
            
            if (result[0] != null) {
                return result[0].future;
            }
        }
        
        ChunkTask task = new ChunkTask(pos, type, world, requestId);
        
        if (enableDeduplication) {
            activeTasks.put(chunkKey, task);
            requestTasks.put(requestId, task);
            activeTaskCount.incrementAndGet();
        } else {
             // Even if deduplication disabled, we might want to track count?
             // Original code only put in map if deduplication enabled.
             // If deduplication disabled, we rely on taskQueue size?
        }
        
        taskQueue.offer(task);
        totalQueued.incrementAndGet();
        
        return task.future;
    }
    
    public ChunkTask getNextTask() {
        return taskQueue.poll();
    }
    
    public void completeTask(ChunkTask task, boolean success) {
        long chunkKey = ChunkPos.asLong(task.position.x, task.position.z);
        
        if (enableDeduplication) {
            if (activeTasks.remove(chunkKey) != null) {
                 activeTaskCount.decrementAndGet();
            }
            requestTasks.remove(task.requestId);
        }
        
        totalCompleted.incrementAndGet();
        totalProcessingTime.addAndGet(System.currentTimeMillis() - task.timestamp);
        
        task.future.complete(success);
    }
    
    public ChunkTask getTask(String requestId) {
        return requestTasks.get(requestId);
    }
    
    public boolean cancelTask(String requestId) {
        ChunkTask task = requestTasks.remove(requestId);
        if (task != null) {
            long chunkKey = ChunkPos.asLong(task.position.x, task.position.z);
            if (activeTasks.remove(chunkKey) != null) {
                activeTaskCount.decrementAndGet();
            }
            taskQueue.remove(task);
            task.future.cancel(false);
            return true;
        }
        return false;
    }
    
    public QueueStats getStats() {
        return new QueueStats(
            taskQueue.size(),
            activeTaskCount.get(),
            totalQueued.get(),
            totalCompleted.get(),
            totalDeduplicated.get(),
            totalProcessingTime.get(),
            getAverageProcessingTime()
        );
    }
    
    private double getAverageProcessingTime() {
        int completed = totalCompleted.get();
        return completed > 0 ? (double) totalProcessingTime.get() / completed : 0.0;
    }
    
    /**
     * Get tasks by type
     */
    public List<ChunkTask> getTasksByType(TaskType type) {
        List<ChunkTask> tasks = new ArrayList<>();
        for (ChunkTask task : activeTasks.values()) {
            if (task.type == type) {
                tasks.add(task);
            }
        }
        return tasks;
    }
    
    /**
     * Clear all tasks (emergency use)
     */
    public void clearAll() {
        taskQueue.clear();
        activeTasks.clear();
        requestTasks.clear();
        
        // Cancel all futures
        for (ChunkTask task : requestTasks.values()) {
            task.future.cancel(false);
        }
    }
    
    /**
     * Shutdown queue
     */
    public void shutdown() {
        System.out.println("[TurboMC][UnifiedQueue] Shutting down...");
        clearAll();
    }
    
    /**
     * Queue statistics
     */
    public static class QueueStats {
        public final int queueSize;
        public final int activeTasks;
        public final int totalQueued;
        public final int totalCompleted;
        public final int totalDeduplicated;
        public final long totalProcessingTime;
        public final double averageProcessingTime;
        
        public QueueStats(int queueSize, int activeTasks, int totalQueued, int totalCompleted,
                          int totalDeduplicated, long totalProcessingTime, double averageProcessingTime) {
            this.queueSize = queueSize;
            this.activeTasks = activeTasks;
            this.totalQueued = totalQueued;
            this.totalCompleted = totalCompleted;
            this.totalDeduplicated = totalDeduplicated;
            this.totalProcessingTime = totalProcessingTime;
            this.averageProcessingTime = averageProcessingTime;
        }
        
        @Override
        public String toString() {
            return String.format("Queue[queued=%d, active=%d, completed=%d, dedup=%d, avgTime=%.2fms]",
                               queueSize, activeTasks, totalCompleted, totalDeduplicated, averageProcessingTime);
        }
    }
}
