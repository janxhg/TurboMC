package com.turbomc.inspector;

import com.turbomc.TurboMCPlugin;
import org.bukkit.configuration.ConfigurationSection;

public class WebViewerConfig {
    private final TurboMCPlugin plugin;
    
    public WebViewerConfig(TurboMCPlugin plugin) {
        this.plugin = plugin;
    }
    
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("web-viewer.enabled", false);
    }
    
    public String getHost() {
        return plugin.getConfig().getString("web-viewer.host", "0.0.0.0");
    }
    
    public int getPort() {
        return plugin.getConfig().getInt("web-viewer.port", 8080);
    }
    
    public int getMaxConnections() {
        return plugin.getConfig().getInt("web-viewer.max-connections", 50);
    }
    
    public int getAutoRefreshInterval() {
        return plugin.getConfig().getInt("web-viewer.auto-refresh-interval", 30);
    }
    
    // Map display settings
    public double getDefaultZoom() {
        return plugin.getConfig().getDouble("web-viewer.map-display.default-zoom", 1.0);
    }
    
    public double getMaxZoom() {
        return plugin.getConfig().getDouble("web-viewer.map-display.max-zoom", 5.0);
    }
    
    public double getMinZoom() {
        return plugin.getConfig().getDouble("web-viewer.map-display.min-zoom", 0.1);
    }
    
    public boolean showChunkBoundaries() {
        return plugin.getConfig().getBoolean("web-viewer.map-display.show-chunk-boundaries", true);
    }
    
    public boolean showRegionBoundaries() {
        return plugin.getConfig().getBoolean("web-viewer.map-display.show-region-boundaries", true);
    }
    
    public boolean showCoordinateGrid() {
        return plugin.getConfig().getBoolean("web-viewer.map-display.show-coordinate-grid", true);
    }
    
    public int getChunkPixelSize() {
        return plugin.getConfig().getInt("web-viewer.map-display.chunk-pixel-size", 16);
    }
    
    // Chunk analysis settings
    public boolean isCorruptionDetection() {
        return plugin.getConfig().getBoolean("web-viewer.chunk-analysis.corruption-detection", true);
    }
    
    public boolean highlightCorrupted() {
        return plugin.getConfig().getBoolean("web-viewer.chunk-analysis.highlight-corrupted", true);
    }
    
    public boolean showCompressionStats() {
        return plugin.getConfig().getBoolean("web-viewer.chunk-analysis.show-compression-stats", true);
    }
    
    public boolean showModificationTime() {
        return plugin.getConfig().getBoolean("web-viewer.chunk-analysis.show-modification-time", true);
    }
    
    public String getColorScheme() {
        return plugin.getConfig().getString("web-viewer.chunk-analysis.color-scheme", "default");
    }
    
    // Interactive features
    public boolean isClickInspect() {
        return plugin.getConfig().getBoolean("web-viewer.interactive-features.click-inspect", true);
    }
    
    public boolean isHoverInfo() {
        return plugin.getConfig().getBoolean("web-viewer.interactive-features.hover-info", true);
    }
    
    public boolean showMinimap() {
        return plugin.getConfig().getBoolean("web-viewer.interactive-features.show-minimap", true);
    }
    
    public boolean isSearchEnabled() {
        return plugin.getConfig().getBoolean("web-viewer.interactive-features.enable-search", true);
    }
    
    public boolean isExportEnabled() {
        return plugin.getConfig().getBoolean("web-viewer.interactive-features.enable-export", true);
    }
    
    public boolean isRealTimeUpdates() {
        return plugin.getConfig().getBoolean("web-viewer.interactive-features.real-time-updates", true);
    }
    
    // Performance settings
    public int getMaxChunksRender() {
        return plugin.getConfig().getInt("web-viewer.performance.max-chunks-render", 1000);
    }
    
    public boolean isTileCachingEnabled() {
        return plugin.getConfig().getBoolean("web-viewer.performance.enable-tile-caching", true);
    }
    
    public int getCacheSizeMB() {
        return plugin.getConfig().getInt("web-viewer.performance.cache-size-mb", 256);
    }
    
    public boolean isLazyLoading() {
        return plugin.getConfig().getBoolean("web-viewer.performance.lazy-loading", true);
    }
    
    public boolean isPreloadSurrounding() {
        return plugin.getConfig().getBoolean("web-viewer.performance.preload-surrounding", true);
    }
    
    // Security settings
    public boolean requireAuth() {
        return plugin.getConfig().getBoolean("web-viewer.security.require-auth", false);
    }
    
    public String getApiKey() {
        return plugin.getConfig().getString("web-viewer.security.api-key", "");
    }
    
    public String getAllowedOrigins() {
        return plugin.getConfig().getString("web-viewer.security.allowed-origins", "*");
    }
    
    public int getRateLimit() {
        return plugin.getConfig().getInt("web-viewer.security.rate-limit", 100);
    }
    
    public boolean isHttpsEnabled() {
        return plugin.getConfig().getBoolean("web-viewer.security.enable-https", false);
    }
}
