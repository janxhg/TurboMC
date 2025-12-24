package com.turbomc.test.streaming;

import com.turbomc.streaming.IntentPredictor;
import com.turbomc.config.TurboConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class IntentPredictorTest {

    private IntentPredictor predictor;

    @BeforeEach
    public void setup() throws Exception {
        // Reset config to ensure defaults
        File turboConfigFile = new File("turbo.toml");
        if (turboConfigFile.exists()) {
            turboConfigFile.delete();
        }
        TurboConfig.resetInstance();
        predictor = new IntentPredictor();
    }

    @Test
    public void testStationaryPrediction() {
        // Feed stationary points
        predictor.update(0, 0);
        try { Thread.sleep(50); } catch (Exception e) {}
        predictor.update(0, 0);
        
        List<int[]> prediction = predictor.predict(0, 0, 10);
        
        // Should be empty or very few chunks
        assertTrue(prediction.isEmpty(), "Stationary player should yield no directional prediction");
    }

    @Test
    public void testLinearMovement() throws InterruptedException {
        // Simulate moving North (Z decreasing) at speed
        // T=0: 0, 10
        // T=100ms: 0, 9
        // T=200ms: 0, 8
        // Velocity: -1 chunk / 100ms = -10 chunks/sec (Very fast!)
        
        long t0 = System.currentTimeMillis();
        
        // Can't easily mock time inside IntentPredictor without refactoring, 
        // so we start with a "warm up" sequence using realThread.sleep
        // Note: IntentPredictor filters dt > 0.05s (50ms)
        
        predictor.update(0, 20);
        Thread.sleep(100);
        predictor.update(0, 18); // moved 2 chunks
        Thread.sleep(100);
        predictor.update(0, 16); // moved 2 chunks
        
        List<int[]> prediction = predictor.predict(0, 16, 12);
        
        assertFalse(prediction.isEmpty(), "Should have predicted chunks");
        
        // Expecting prediction further in -Z direction (e.g., 0, 14; 0, 12...)
        int[] first = prediction.get(0);
        assertTrue(first[1] < 16, "First predicted chunk should be < 16 Z (moving North)");
        
        // Verify multiple chunks predicted
        assertTrue(prediction.size() > 5, "Should predict a tunnel of chunks");
    }
    
    @Test
    public void testElytraBoostMultiplier() throws InterruptedException {
        // Simulate INSANE speed (Elytra + rockets)
        // 5 chunks per 100ms = 50 chunks/sec
        
        predictor.update(0, 100);
        Thread.sleep(100);
        predictor.update(0, 95);
        
        // Base lookahead 10
        List<int[]> prediction = predictor.predict(0, 95, 10);
        
        // With default Elytra multiplier (4.0), lookahead should be 40.
        // Tunnel contains width * length. 
        // We just check if the furthest chunk is far enough.
        
        int minZ = Integer.MAX_VALUE;
        for (int[] p : prediction) {
            if (p[1] < minZ) minZ = p[1];
        }
        
        // Started at 95. Expecting to go down to 95 - 40 = 55.
        // Allow some variance, but should be significantly lower than 95 - 10 = 85.
        assertTrue(minZ < 80, "Prediction should extend significantly due to Elytra multiplier");
    }
}
