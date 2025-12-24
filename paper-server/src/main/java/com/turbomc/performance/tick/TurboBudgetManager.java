package com.turbomc.performance.tick;

import com.turbomc.config.TurboConfig;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the global tick budget to ensure deterministic performance.
 * If a subsystem exceeds its allocated budget, it will suggest yielding.
 */
public class TurboBudgetManager {
    private static final TurboBudgetManager INSTANCE = new TurboBudgetManager();
    
    public enum Subsystem {
        ENTITY(0.60),
        BLOCK_ENTITY(0.20),
        WORLD(0.20),
        GLOBAL(1.0);

        private final double allocation;
        Subsystem(double allocation) { this.allocation = allocation; }
        public double getAllocation() { return allocation; }
    }

    private final AtomicLong tickStartTime = new AtomicLong(0);
    private final AtomicLong currentBudgetNs = new AtomicLong(0);

    public static TurboBudgetManager getInstance() { return INSTANCE; }

    public void startTick() {
        if (!TurboConfig.isGlobalBudgetEnabled()) return;
        tickStartTime.set(System.nanoTime());
        double budgetMs = TurboConfig.getTickBudgetMs();
        currentBudgetNs.set((long) (budgetMs * 1_000_000));
    }

    public boolean shouldYield(Subsystem subsystem) {
        if (!TurboConfig.isGlobalBudgetEnabled()) return false;
        
        long elapsed = System.nanoTime() - tickStartTime.get();
        // Global limit is strict
        if (elapsed > currentBudgetNs.get()) return true;
        
        // Subsystem limits are soft suggestions (can be tweaked)
        long allocatedNs = (long) (currentBudgetNs.get() * subsystem.getAllocation());
        // For now, only yield if we are WAY over or if global is exceeded
        // We might want more sophisticated logic later
        return elapsed > allocatedNs && subsystem != Subsystem.GLOBAL;
    }
    
    public long getElapsedNs() {
        return System.nanoTime() - tickStartTime.get();
    }
}
