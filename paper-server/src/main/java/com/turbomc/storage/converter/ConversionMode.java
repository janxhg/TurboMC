package com.turbomc.storage.converter;

/**
 * LRF conversion modes for TurboMC storage system.
 * 
 * Determines how and when MCA files are converted to LRF format.
 */
public enum ConversionMode {
    /**
     * Convert MCA to LRF on-demand as chunks are loaded.
     * 
     * Characteristics:
     * - Gradual conversion during gameplay
     * - Lower initial startup time
     * - Conversion happens when chunks are accessed
     * - Better for large servers with many worlds
     */
    ON_DEMAND("on-demand"),
    
    /**
     * Convert MCA to LRF during server idle time/background.
     * 
     * Characteristics:
     * - Conversion during low server load periods
     * - Balanced approach between startup time and performance
     * - Intelligent scheduling during idle moments
     * - Good for medium servers that want balanced performance
     */
    BACKGROUND("background"),
    
    /**
     * Convert ALL MCA files to LRF during server startup.
     * 
     * Characteristics:
     * - Complete conversion before world loading
     * - Higher initial startup time
     * - Maximum optimization from the start
     * - Better for smaller servers or fresh installations
     */
    FULL_LRF("full-lrf"),
    
    /**
     * Manual conversion only - no automatic conversion.
     * 
     * Characteristics:
     * - No automatic conversion
     * - Requires manual intervention
     * - For advanced users who want full control
     */
    MANUAL("manual");
    
    private final String configValue;
    
    ConversionMode(String configValue) {
        this.configValue = configValue;
    }
    
    /**
     * Get the configuration value used in turbo.toml.
     * 
     * @return Configuration string value
     */
    public String getConfigValue() {
        return configValue;
    }
    
    /**
     * Parse conversion mode from configuration string.
     * 
     * @param value Configuration value
     * @return Conversion mode (defaults to ON_DEMAND if unknown)
     */
    public static ConversionMode fromString(String value) {
        if (value == null) {
            return ON_DEMAND;
        }
        
        String normalized = value.toLowerCase().trim();
        
        switch (normalized) {
            case "on-demand":
            case "on_demand":
            case "ondemand":
                return ON_DEMAND;
                
            case "background":
            case "bg":
                return BACKGROUND;
                
            case "full-lrf":
            case "full_lrf":
            case "fulllrf":
            case "full":
                return FULL_LRF;
                
            case "manual":
            case "none":
                return MANUAL;
                
            default:
                return ON_DEMAND;
        }
    }
    
    /**
     * Check if this mode requires automatic conversion.
     * 
     * @return true if automatic conversion is enabled
     */
    public boolean isAutomaticConversion() {
        return this != MANUAL;
    }
    
    /**
     * Check if this mode requires full conversion at startup.
     * 
     * @return true if full conversion is performed at startup
     */
    public boolean isFullConversionAtStartup() {
        return this == FULL_LRF;
    }
    
    /**
     * Check if this mode performs background conversion.
     * 
     * @return true if background conversion is performed
     */
    public boolean isBackgroundConversion() {
        return this == BACKGROUND;
    }
    
    @Override
    public String toString() {
        return configValue;
    }
}
