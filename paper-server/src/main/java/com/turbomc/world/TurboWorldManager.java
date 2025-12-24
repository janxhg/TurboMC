package com.turbomc.world;

import net.minecraft.server.level.ServerLevel;
import com.turbomc.config.TurboConfig;
import com.turbomc.world.ParallelChunkGenerator;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manager for parallel chunk generators per world.
 * 
 * @author TurboMC
 * @version 2.1.0
 */
public class TurboWorldManager {
    
    private static TurboWorldManager instance;
    private final ConcurrentHashMap<String, ParallelChunkGenerator> generators;
    private final TurboConfig config;
    private final boolean enabled;
    
    private TurboWorldManager() {
        this.config = TurboConfig.getInstance();
        this.generators = new ConcurrentHashMap<>();
        this.enabled = config.getBoolean("world.generation.parallel-enabled", true);
        
        if (enabled) {
            System.out.println("[TurboMC][WorldManager] Parallel generation enabled");
        }
    }
    
    public static TurboWorldManager getInstance() {
        if (instance == null) {
            synchronized (TurboWorldManager.class) {
                if (instance == null) {
                    instance = new TurboWorldManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * Get or create parallel generator for a world.
     */
    public ParallelChunkGenerator getGenerator(ServerLevel world) {
        if (!enabled) {
            return null;
        }
        
        String worldName = world.dimension().location().toString();
        return generators.computeIfAbsent(worldName, k -> {
            return new ParallelChunkGenerator(world, config);
        });
    }
    
    /**
     * Shutdown all generators.
     */
    public void shutdown() {
        System.out.println("[TurboMC][WorldManager] Shutting down parallel generators...");
        for (ParallelChunkGenerator generator : generators.values()) {
            generator.shutdown();
        }
        generators.clear();
    }
    
    /**
     * Get statistics for all generators.
     */
    public String getStats() {
        if (generators.isEmpty()) {
            return "No active generators";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("[TurboMC][WorldManager] Generator Stats:\n");
        for (var entry : generators.entrySet()) {
            sb.append("  ").append(entry.getKey()).append(": ")
              .append(entry.getValue().getStats()).append("\n");
        }
        return sb.toString();
    }
}
