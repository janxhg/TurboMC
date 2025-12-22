package com.turbomc.performance;

import com.turbomc.config.TurboConfig;

/**
 * Base interface for all TurboMC optimizer modules.
 * Provides standardized interface for performance optimization modules.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public interface TurboOptimizerModule {
    
    /**
     * Initialize the module with configuration
     */
    void initialize();
    
    /**
     * Start the module's optimization processes
     */
    void start();
    
    /**
     * Stop the module's optimization processes
     */
    void stop();
    
    /**
     * Check if the module is enabled
     * @return true if enabled, false otherwise
     */
    boolean isEnabled();
    
    /**
     * Get the module name
     * @return module name
     */
    String getModuleName();
    
    /**
     * Get current performance statistics
     * @return performance statistics string
     */
    String getPerformanceStats();
    
    /**
     * Load configuration from TOML
     * @param config TurboConfig instance
     */
    void loadConfiguration(TurboConfig config);
    
    /**
     * Check if module should run based on current server conditions
     * @return true if should optimize, false otherwise
     */
    boolean shouldOptimize();
    
    /**
     * Perform optimization cycle
     */
    void performOptimization();
}
