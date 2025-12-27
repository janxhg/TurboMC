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
    
    // Statistics
    private final AtomicInteger totalQueued = new AtomicInteger(0);
    private final AtomicInteger totalCompleted = new AtomicInteger(0);
    private final AtomicInteger totalDeduplicated = new AtomicInteger(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    
    // Configuration
    private final int maxQueueSize;
    private final int maxConcurrentTasks;
    private final boolean enableDeduplication;
    
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
        this.taskQueue = new PriorityBlockingQueue<>(maxQueueSize, 
            Comparator.comparingInt((ChunkTask t) -> t.type.priority)
                     .thenComparingLong(t -> t.timestamp));
        
        System.out.println("[TurboMC][UnifiedQueue] Initialized: maxQueue=" + maxQueueSize + 
                          ", maxConcurrent=" + maxConcurrentTasks + 
                          ", deduplication=" + enableDeduplication);
    }
    
    /**
     * Queue a chunk task with automatic deduplication
     */
    public CompletableFuture<Boolean> queueTask(ChunkPos pos, TaskType type, ServerLevel world, String requestId) {
        if (activeTasks.size() >= maxConcurrentTasks) {
            return CompletableFuture.completedFuture(false); // Queue full
        }
        
        if (taskQueue.size() >= maxQueueSize) {
            return CompletableFuture.completedFuture(false); // Queue full
        }
        
        long chunkKey = ChunkPos.asLong(pos.x, pos.z);
        
        // Deduplication check
        if (enableDeduplication) {
            ChunkTask existing = activeTasks.get(chunkKey);
            if (existing != null) {
                // Check if new task has higher priority
                if (type.priority < existing.type.priority) {
                    // Replace with higher priority task
                    activeTasks.remove(chunkKey);
                    requestTasks.remove(existing.requestId);
                    totalDeduplicated.incrementAndGet();
                } else {
                    // Keep existing task
                    return existing.future;
                }
            }
        }
        
        ChunkTask task = new ChunkTask(pos, type, world, requestId);
        
        if (enableDeduplication) {
            activeTasks.put(chunkKey, task);
            requestTasks.put(requestId, task);
        }
        
        taskQueue.offer(task);
        totalQueued.incrementAndGet();
        
        return task.future;
    }
    
    /**
     * Get next task for processing
     */
    public ChunkTask getNextTask() {
        return taskQueue.poll();
    }
    
    /**
     * Mark task as completed
     */
    public void completeTask(ChunkTask task, boolean success) {
        long chunkKey = ChunkPos.asLong(task.position.x, task.position.z);
        
        if (enableDeduplication) {
            activeTasks.remove(chunkKey);
            requestTasks.remove(task.requestId);
        }
        
        totalCompleted.incrementAndGet();
        totalProcessingTime.addAndGet(System.currentTimeMillis() - task.timestamp);
        
        task.future.complete(success);
    }
    
    /**
     * Get task by request ID
     */
    public ChunkTask getTask(String requestId) {
        return requestTasks.get(requestId);
    }
    
    /**
     * Cancel task by request ID
     */
    public boolean cancelTask(String requestId) {
        ChunkTask task = requestTasks.remove(requestId);
        if (task != null) {
            long chunkKey = ChunkPos.asLong(task.position.x, task.position.z);
            activeTasks.remove(chunkKey);
            taskQueue.remove(task);
            task.future.cancel(false);
            return true;
        }
        return false;
    }
    
    /**
     * Get queue statistics
     */
    public QueueStats getStats() {
        return new QueueStats(
            taskQueue.size(),
            activeTasks.size(),
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
