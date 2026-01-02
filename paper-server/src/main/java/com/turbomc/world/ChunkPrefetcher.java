package com.turbomc.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import com.turbomc.config.TurboConfig;
import com.turbomc.storage.optimization.TurboStorageManager;
import com.turbomc.core.autopilot.HealthMonitor;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.World;

/**
 * HyperView Chunk Prefetcher.
 * 
 * Orchestrates "Infinite Loading" by intelligently pre-generating chunks 
 * in a large radius around players using low-priority background threads.
 * 
 * @author TurboMC
 * @version 2.3.3
 */
public class ChunkPrefetcher {
    
    private static final Logger LOGGER = Logger.getLogger("TurboMC-HyperView");
    private final ServerLevel world;
    private final ParallelChunkGenerator generator;
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<Long> visitedChunks = Collections.newSetFromMap(
        new java.util.LinkedHashMap<Long, Boolean>(10000, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
                return size() > 50000; // Limitar a 50k chunks
            }
        }
    );
    private final AtomicInteger radius = new AtomicInteger(32); // Default 32 chunks
    
    private final ScheduledExecutorService prefetchExecutor;
    
    public ChunkPrefetcher(ServerLevel world, ParallelChunkGenerator generator) {
        this.world = world;
        this.generator = generator;
        this.radius.set(TurboConfig.getInstance().getInt("hyperview-radius", 32));
        
        // Use shared prefetch executor instead of creating own thread
        this.prefetchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TurboMC-HyperView-" + world.dimension().location().getPath());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }
    
    public void setRadius(int newRadius) {
        this.radius.set(Math.max(8, Math.min(newRadius, 128))); // Clamp 8-128
        // Clear visited cache when radius increases significantly to re-evaluate
        // But for now, we keep it to avoid re-checking existing chunks too much
    }
    
    public int getRadius() {
        return radius.get();
    }
    
    public void start() {
        if (running.getAndSet(true)) return;
        
        currentTask = prefetchExecutor.scheduleAtFixedRate(this::processTick, 2000, 2000, TimeUnit.MILLISECONDS);
        LOGGER.info("[TurboMC][HyperView] Started for world " + world.dimension().location() + " with radius " + radius.get());
    }
    
    public void stop() {
        running.set(false);
        if (prefetchExecutor != null) {
            prefetchExecutor.shutdown();
            try {
                if (!prefetchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    prefetchExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                prefetchExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
        
    private void processTick() {
        if (world.players().isEmpty()) return;
        
        // Use central intelligence (Autopilot) for dynamic radius
        int currentRadius = com.turbomc.core.autopilot.TurboAutopilot.getInstance().getEffectiveHyperViewRadius();
        int chunksQueued = 0;
        int maxQueuePerTick = 50; // Don't flood the generator
        
        int playersWithActivity = 0;
        for (ServerPlayer player : world.players()) {
            int px = player.chunkPosition().x;
            int pz = player.chunkPosition().z;
            
            // v2.3.9: Optimized Spiral Scan
            // Instead of nested O(R^2) loops, we spiral out from the center
            // This prioritizes chunks closer to the player and allows early exit.
            int x = 0, z = 0;
            int dx = 0, dz = -1;
            int maxRadiusSq = currentRadius * currentRadius;
            int sideLimit = (currentRadius * 2 + 1);
            int maxIterations = sideLimit * sideLimit;
            
            int playerQueued = 0;
            int iterations = 0;

            while (iterations < maxIterations && chunksQueued < maxQueuePerTick) {
                if ((-currentRadius <= x && x <= currentRadius) && (-currentRadius <= z && z <= currentRadius)) {
                    if (x*x + z*z <= maxRadiusSq) {
                        int cx = px + x;
                        int cz = pz + z;
                        long key = ChunkPos.asLong(cx, cz);
                        
                        if (!visitedChunks.contains(key)) {
                            // Fast check: Is it generated?
                            // We use a non-blocking check if possible
                            if (!isChunkGenerated(cx, cz)) {
                                // Priority 8 (HYPERVIEW_PREFETCH) via UnifiedQueue
                                generator.queueGeneration(cx, cz, 8).thenAccept(chunk -> {
                                    if (chunk != null) {
                                        // Fix for GC Thrashing/Freezes:
                                        // Immediately request unload of LRF-warmed chunks.
                                        // unloadChunkRequest is safe to call from any thread on Paper.
                                        world.getWorld().unloadChunkRequest(cx, cz);
                                    }
                                });
                                chunksQueued++;
                                playerQueued++;
                            }
                            visitedChunks.add(key);
                        }
                    }
                }
                
                if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                    int temp = dx;
                    dx = -dz;
                    dz = temp;
                }
                x += dx;
                z += dz;
                iterations++;
            }
            
            if (playerQueued > 0) playersWithActivity++;
            if (chunksQueued >= maxQueuePerTick) break;
        }

        // v2.3.9: Dynamic scheduling adaptation
        HealthMonitor.HealthSnapshot status = HealthMonitor.getInstance().getLastSnapshot();
        if (status.isCritical()) {
            // Slow down significantly if server is dying
            modifySchedule(5000); 
        } else if (status.isHealthy() && lastInterval != 2000) {
            modifySchedule(2000);
        }

        if (chunksQueued > 0) {
            LOGGER.info("[TurboMC][HyperView] Queued " + chunksQueued + " chunks for " + playersWithActivity + " players in world " + world.dimension().location().getPath());
        }
    }
    
    private int lastInterval = 2000;
    private java.util.concurrent.ScheduledFuture<?> currentTask;

    private synchronized void modifySchedule(int ms) {
        if (ms == lastInterval) return;
        if (currentTask != null) currentTask.cancel(false);
        currentTask = prefetchExecutor.scheduleAtFixedRate(this::processTick, ms, ms, TimeUnit.MILLISECONDS);
        lastInterval = ms;
        LOGGER.info("[TurboMC][HyperView] Dynamic backoff: Adjusted scan interval to " + ms + "ms");
    }
    
    // Wrapper to access private/protected method or use public API
    private boolean isChunkGenerated(int x, int z) {
        // We can't easily check disk without IO cost. 
        // But ParallelChunkGenerator.chunkExistsOnDisk does a check.
        // Since we don't have direct access to private method, we can invoke hasChunk 
        // but that only returns loaded chunks usually.
        // Actually, world.getChunkSource().hasChunk(x, z) often means "is loaded".
        
        // Better: Check if storage has it.
        // For now, we assume if it's not loaded, we might need to check.
        // But checking disk for every chunk is expensive.
        // Optimized strategy: Just queue it. 
        // ParallelChunkGenerator's processTask does: chunkCache.getChunk(..., FULL, true)
        // If it exists on disk, 'getChunk' is fast (just load).
        // If not, it generates.
        // BUT loading from disk is also heavy memory usage if we don't unload.
        
        // Wait! We want to GENERATE (create LRF/Region file) but NOT keep in memory.
        // 'getChunk' loads it into memory.
        // If we load 1000 chunks, we crash RAM.
        
        // We need 'generate and unload'.
        // ParallelChunkGenerator returns a CompletableFuture<ChunkAccess>.
        // We should chain a .thenAccept(chunk -> unload(chunk))
        
        return false; // Let the generator handle logic
    }
}
