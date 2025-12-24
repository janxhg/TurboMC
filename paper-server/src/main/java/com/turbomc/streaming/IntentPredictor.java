package com.turbomc.streaming;

import com.turbomc.config.TurboConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Predicts future chunk loading patterns based on player movement history (Intent).
 * Used by MMapReadAheadEngine to generate "Probability Tunnels" for prefetching.
 */
public class IntentPredictor {

    private final Deque<MovementPoint> history = new ArrayDeque<>();
    private final TurboConfig config;
    
    // Config values cached for performance
    private int historyWindowMs;
    private int tunnelWidth;
    private double elytraMultiplier;

    public IntentPredictor() {
        this.config = TurboConfig.getInstance();
        refreshConfig();
    }

    public void refreshConfig() {
        this.historyWindowMs = config.getPredictionHistorySeconds() * 1000;
        this.tunnelWidth = config.getPredictionTunnelWidth();
        this.elytraMultiplier = config.getPredictionElytraMultiplier();
    }

    /**
     * Updates the predictor with a new position.
     * Should be called whenever a chunk is accessed (player moves).
     */
    public void update(int chunkX, int chunkZ) {
        long now = System.currentTimeMillis();
        
        // Add new point
        history.addLast(new MovementPoint(chunkX, chunkZ, now));
        
        // Prune old history
        while (!history.isEmpty() && (now - history.peekFirst().timestamp > historyWindowMs)) {
            history.removeFirst();
        }
        
        // Also prune if too many points (limit to 100 to prevent extensive calculation spikes)
        while (history.size() > 100) {
            history.removeFirst();
        }
    }

    /**
     * Generates a list of predicted chunks to prefetch based on current intent.
     * 
     * @param startX Current chunk X
     * @param startZ Current chunk Z
     * @param baseLookahead Base lookahead distance (from config/engine)
     * @return List of int[]{x, z} coordinates to prefetch, or empty list if no prediction possible.
     */
    public List<int[]> predict(int startX, int startZ, int baseLookahead) {
        if (history.size() < 2) {
            return new ArrayList<>(); // Insufficient data
        }

        // 1. Calculate weighted average velocity
        double[] velocity = calculateTrendVelocity();
        double velX = velocity[0];
        double velZ = velocity[1];
        
        // If stationary or moving very slowly, return empty (standard radius prefetch will handle it)
        if (Math.abs(velX) < 0.1 && Math.abs(velZ) < 0.1) {
            return new ArrayList<>();
        }

        List<int[]> predictedChunks = new ArrayList<>();
        
        // 2. Detect "High Speed" Context (Elytra/Trident)
        double speed = Math.sqrt(velX * velX + velZ * velZ);
        double lookahead = baseLookahead;
        
        // If speed > 1.5 chunks/sec (~24 blocks/sec), assume boosting/elytra
        if (speed > 1.5) {
            lookahead *= elytraMultiplier;
        } else {
            // Slight boost for running
            lookahead *= 1.2;
        }

        // Cap lookahead to avoid generating the entire world
        lookahead = Math.min(lookahead, 64); // Cap at 64 chunks

        // 3. Project the "Probability Tunnel"
        // We step forward along the velocity vector
        double currentX = startX;
        double currentZ = startZ;
        
        // Normalize velocity for stepping
        double stepX = velX / speed; // Unit vector
        double stepZ = velZ / speed;
        
        // Step size: 1 chunk
        int steps = (int) Math.ceil(lookahead);
        
        for (int i = 1; i <= steps; i++) {
            currentX += stepX;
            currentZ += stepZ;
            
            int pX = (int) Math.round(currentX);
            int pZ = (int) Math.round(currentZ);
            
            // Add chunks in the "width" of the tunnel
            // We draw a perpendicular line to the movement vector
            // Perpendicular vector (-stepZ, stepX)
            
            for (int w = -tunnelWidth; w <= tunnelWidth; w++) {
                int offX = (int) (pX + ((-stepZ) * w));
                int offZ = (int) (pZ + ((stepX) * w));
                
                predictedChunks.add(new int[]{offX, offZ});
            }
        }

        return predictedChunks;
    }

    /**
     * Calculates the average velocity (chunks/tick equivalent, but relative to sample rate)
     * weighted towards recent movement.
     */
    private double[] calculateTrendVelocity() {
        if (history.isEmpty()) return new double[]{0, 0};

        double totalVelX = 0;
        double totalVelZ = 0;
        double totalWeight = 0;
        
        MovementPoint prev = null;
        int i = 0;
        
        // Linear weighting: older points have less weight
        for (MovementPoint curr : history) {
            if (prev != null) {
                double dt = (curr.timestamp - prev.timestamp) / 1000.0; // Seconds
                if (dt > 0.05) { // filtered small jitters
                    double vx = (curr.x - prev.x) / dt; // chunks per second
                    double vz = (curr.z - prev.z) / dt;
                    
                    double weight = i + 1; // higher i = more recent
                    totalVelX += vx * weight;
                    totalVelZ += vz * weight;
                    totalWeight += weight;
                }
            }
            prev = curr;
            i++;
        }
        
        if (totalWeight == 0) return new double[]{0, 0};
        
        return new double[]{totalVelX / totalWeight, totalVelZ / totalWeight};
    }

    private static class MovementPoint {
        final int x;
        final int z;
        final long timestamp;

        MovementPoint(int x, int z, long timestamp) {
            this.x = x;
            this.z = z;
            this.timestamp = timestamp;
        }
    }
}
