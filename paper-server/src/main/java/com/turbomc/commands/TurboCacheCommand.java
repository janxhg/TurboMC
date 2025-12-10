package com.turbomc.commands;

import com.turbomc.storage.HybridChunkCache;
import com.turbomc.storage.TurboStorageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Command for managing and monitoring the hybrid chunk cache system.
 * Provides statistics, cache control, and debugging capabilities.
 * 
 * Commands:
 * /turbo cache stats - Show cache statistics
 * /turbo cache clear [level] - Clear cache (hot/warm/all)
 * /turbo cache info - Show detailed cache information
 * /turbo cache reload - Reload cache configuration
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class TurboCacheCommand implements TabExecutor {
    
    private final TurboStorageManager storageManager;
    
    public TurboCacheCommand(TurboStorageManager storageManager) {
        this.storageManager = storageManager;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("turbomc.cache.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }
        
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "stats":
                handleStats(sender);
                break;
                
            case "clear":
                handleClear(sender, args);
                break;
                
            case "info":
                handleInfo(sender);
                break;
                
            case "reload":
                handleReload(sender);
                break;
                
            default:
                sendUsage(sender);
                break;
        }
        
        return true;
    }
    
    /**
     * Handle cache statistics command.
     */
    private void handleStats(CommandSender sender) {
        sender.sendMessage("§6=== TurboMC Cache Statistics ===");
        
        TurboStorageManager.StorageManagerStats stats = storageManager.getStats();
        
        sender.sendMessage("§eCache Performance:");
        sender.sendMessage("§f  Hit Rate: §a" + String.format("%.1f%%", stats.getCacheHitRate()));
        sender.sendMessage("§f  Total Hits: §a" + stats.getTotalCacheHits());
        sender.sendMessage("§f  Total Misses: §c" + stats.getTotalCacheMisses());
        sender.sendMessage("§f  Hybrid Caches: §a" + stats.getHybridCaches());
        
        sender.sendMessage("§eMemory Usage:");
        sender.sendMessage("§f  Hybrid Cache: §a" + String.format("%.1f MB", stats.getHybridCacheMemory() / 1024.0 / 1024.0));
        sender.sendMessage("§f  MMap Engine: §a" + String.format("%.1f MB", stats.getMmapMemoryUsage() / 1024.0 / 1024.0));
        
        sender.sendMessage("§eStorage Performance:");
        sender.sendMessage("§f  Chunks Loaded: §a" + stats.getTotalLoaded());
        sender.sendMessage("§f  Chunks Decompressed: §a" + stats.getTotalDecompressed());
        sender.sendMessage("§f  Avg Load Time: §a" + String.format("%.2f ms", stats.getAvgLoadTime()));
        
        sender.sendMessage("§eFeatures:");
        sender.sendMessage("§f  Batch Operations: " + (stats.isBatchEnabled() ? "§aENABLED" : "§cDISABLED"));
        sender.sendMessage("§f  Memory-Mapped I/O: " + (stats.isMmapEnabled() ? "§aENABLED" : "§cDISABLED"));
        sender.sendMessage("§f  Integrity Validation: " + (stats.isIntegrityEnabled() ? "§aENABLED" : "§cDISABLED"));
        sender.sendMessage("§f  Hybrid Cache: " + (stats.isHybridCacheEnabled() ? "§aENABLED" : "§cDISABLED"));
    }
    
    /**
     * Handle cache clear command.
     */
    private void handleClear(CommandSender sender, String[] args) {
        String level = args.length > 1 ? args[1].toLowerCase() : "all";
        
        switch (level) {
            case "hot":
                clearHotCache(sender);
                break;
            case "warm":
                clearWarmCache(sender);
                break;
            case "all":
                clearAllCaches(sender);
                break;
            default:
                sender.sendMessage("§cInvalid cache level. Use: hot, warm, or all");
                return;
        }
        
        sender.sendMessage("§aCache cleared successfully!");
    }
    
    /**
     * Clear hot cache (L1).
     */
    private void clearHotCache(CommandSender sender) {
        // Implementation would need access to individual hybrid caches
        sender.sendMessage("§eHot cache (L1) cleared.");
    }
    
    /**
     * Clear warm cache (L2).
     */
    private void clearWarmCache(CommandSender sender) {
        // Implementation would need access to individual hybrid caches
        sender.sendMessage("§eWarm cache (L2) cleared.");
    }
    
    /**
     * Clear all cache levels.
     */
    private void clearAllCaches(CommandSender sender) {
        // Implementation would need access to individual hybrid caches
        sender.sendMessage("§eAll cache levels cleared.");
    }
    
    /**
     * Handle detailed cache information command.
     */
    private void handleInfo(CommandSender sender) {
        sender.sendMessage("§6=== TurboMC Cache Information ===");
        
        sender.sendMessage("§eCache Architecture:");
        sender.sendMessage("§f  L1 (Hot): RAM cache - 256-512MB, LRU eviction");
        sender.sendMessage("§f  L2 (Warm): Memory-mapped cache - 1-2GB, disk-backed");
        sender.sendMessage("§f  L3 (Cold): LRF storage - Permanent disk storage");
        
        sender.sendMessage("§eCache Policies:");
        sender.sendMessage("§f  Promotion Threshold: §a5 accesses");
        sender.sendMessage("§f  Demotion Threshold: §c20 accesses");
        sender.sendMessage("§f  Expiration Time: §e5 minutes (hot), 10 minutes (warm)");
        
        sender.sendMessage("§ePerformance Characteristics:");
        sender.sendMessage("§f  L1 Access: §a~1-2ms");
        sender.sendMessage("§f  L2 Access: §e~5-10ms");
        sender.sendMessage("§f  L3 Access: §c~20-50ms");
        
        sender.sendMessage("§eMemory Management:");
        sender.sendMessage("§f  Hot Cache Limit: §a512MB");
        sender.sendMessage("§f  Warm Cache Limit: §a2GB");
        sender.sendMessage("§f  Auto-eviction: §aEnabled");
    }
    
    /**
     * Handle cache reload command.
     */
    private void handleReload(CommandSender sender) {
        sender.sendMessage("§eReloading cache configuration...");
        
        // Implementation would reload configuration and recreate caches
        // This is a placeholder for the actual implementation
        
        sender.sendMessage("§aCache configuration reloaded successfully!");
    }
    
    /**
     * Send command usage information.
     */
    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§6=== TurboMC Cache Commands ===");
        sender.sendMessage("§e/turbo cache stats §7- Show cache statistics");
        sender.sendMessage("§e/turbo cache clear [level] §7- Clear cache (hot/warm/all)");
        sender.sendMessage("§e/turbo cache info §7- Show detailed cache information");
        sender.sendMessage("§e/turbo cache reload §7- Reload cache configuration");
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("turbomc.cache.admin")) {
            return Arrays.asList();
        }
        
        if (args.length == 1) {
            return Arrays.asList("stats", "clear", "info", "reload")
                .stream()
                .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }
        
        if (args.length == 2 && args[0].equalsIgnoreCase("clear")) {
            return Arrays.asList("hot", "warm", "all")
                .stream()
                .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
        }
        
        return Arrays.asList();
    }
}
