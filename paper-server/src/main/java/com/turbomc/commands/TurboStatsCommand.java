package com.turbomc.commands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import com.turbomc.storage.optimization.TurboStorageManager;
import com.turbomc.world.TurboWorldManager;
import net.minecraft.server.MinecraftServer;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/**
 * Unified stats command for TurboMC.
 * Shows comprehensive system statistics in a clean dashboard format.
 * 
 * @author TurboMC
 * @version 2.3.0
 */
public class TurboStatsCommand {

    public static void register(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        dispatcher.register(net.minecraft.commands.Commands.literal("turbo")
            .then(net.minecraft.commands.Commands.literal("stats")
                .requires(source -> source.hasPermission(2, "turbomc.command.stats"))
                .executes(context -> {
                    execute(context.getSource().getBukkitSender());
                    return 1;
                })
            )
        );
    }
    
    public static void execute(CommandSender sender) {
        sender.sendMessage("§6╔═══════════════════════════════════════════╗");
        sender.sendMessage("§6║  §e§lTurboMC Performance Dashboard§6        ║");
        sender.sendMessage("§6╠═══════════════════════════════════════════╣");
        
        // Server Performance
        showServerStats(sender);
        sender.sendMessage("§6╟───────────────────────────────────────────╢");
        
        // Storage Stats
        showStorageStats(sender);
        sender.sendMessage("§6╟───────────────────────────────────────────╢");
        
        // Parallel Generation Stats (v2.2.0)
        showGenerationStats(sender);
        sender.sendMessage("§6╟───────────────────────────────────────────╢");
        
        // Memory Stats
        showMemoryStats(sender);
        
        sender.sendMessage("§6╚═══════════════════════════════════════════╝");
    }
    
    private static void showServerStats(CommandSender sender) {
        double[] tpsArray = org.bukkit.Bukkit.getTPS();
        double tps = tpsArray[0];
        
        String tpsColor = tps >= 19.5 ? "§a" : tps >= 18.0 ? "§e" : "§c";
        String tpsBar = createBar(tps / 20.0);
        
        sender.sendMessage("§6║ §eServer Performance:");
        sender.sendMessage("§6║   §7TPS: " + tpsColor + String.format("%.2f", tps) + " §7" + tpsBar);
        
        int players = org.bukkit.Bukkit.getOnlinePlayers().size();
        sender.sendMessage("§6║   §7Players: §a" + players + " §7online");
        
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        sender.sendMessage("§6║   §7Uptime: §f" + formatDuration(uptime));
    }
    
    private static void showStorageStats(CommandSender sender) {
        sender.sendMessage("§6║ §eStorage Engine:");
        
        try {
            TurboStorageManager mgr = TurboStorageManager.getInstance();
            if (mgr != null) {
                // Get basic stats
                sender.sendMessage("§6║   §7Active Regions: §a" + mgr.getActiveRegionCount());
                sender.sendMessage("§6║   §7Batch Loaders: §a" + mgr.getBatchLoaderCount());
                sender.sendMessage("§6║   §7Batch Savers: §a" + mgr.getBatchSaverCount());
                
                // Cache stats if available
                long cacheHits = mgr.getCacheHits();
                long cacheMisses = mgr.getCacheMisses();
                long total = cacheHits + cacheMisses;
                
                if (total > 0) {
                    double hitRate = (100.0 * cacheHits) / total;
                    String hitBar = createBar(hitRate / 100.0);
                    String hitColor = hitRate >= 90 ? "§a" : hitRate >= 70 ? "§e" : "§c";
                    sender.sendMessage("§6║   §7Cache Hit: " + hitColor + String.format("%.1f%%", hitRate) + " §7" + hitBar);
                }
            } else {
                sender.sendMessage("§6║   §cStorage manager not initialized");
            }
        } catch (Exception e) {
            sender.sendMessage("§6║   §cError: " + e.getMessage());
        }
    }
    
    private static void showGenerationStats(CommandSender sender) {
        sender.sendMessage("§6║ §eParallel Generation (v2.2.0):");
        
        try {
            TurboWorldManager worldMgr = TurboWorldManager.getInstance();
            String stats = worldMgr.getStats();
            
            if (stats.contains("No active generators")) {
                sender.sendMessage("§6║   §7Status: §eIdle");
            } else {
                // Parse and display stats
                sender.sendMessage("§6║   §7Status: §aActive");
                String[] lines = stats.split("\n");
                for (String line : lines) {
                    if (line.contains("ParallelGen{")) {
                        // Extract key metrics
                        if (line.contains("generated=")) {
                            int generated = extractInt(line, "generated=");
                            sender.sendMessage("§6║   §7Generated: §a" + generated + " §7chunks");
                        }
                        if (line.contains("avgTime=")) {
                            double avgTime = extractDouble(line, "avgTime=");
                            String timeColor = avgTime < 50 ? "§a" : avgTime < 100 ? "§e" : "§c";
                            sender.sendMessage("§6║   §7Avg Time: " + timeColor + String.format("%.1f", avgTime) + "ms");
                        }
                        if (line.contains("pending=")) {
                            int pending = extractInt(line, "pending=");
                            sender.sendMessage("§6║   §7Pending: §e" + pending);
                        }
                    } else if (line.contains("HyperView Radius:")) {
                        String radius = line.split(":")[1].trim();
                        sender.sendMessage("§6║   §7Radius: §b" + radius + " §7chunks");
                    }
                }
            }
        } catch (Exception e) {
            sender.sendMessage("§6║   §cError: " + e.getMessage());
        }
    }
    
    private static void showMemoryStats(CommandSender sender) {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        
        long used = heapUsage.getUsed();
        long max = heapUsage.getMax();
        double usagePercent = (100.0 * used) / max;
        
        String memBar = createBar(usagePercent / 100.0);
        String memColor = usagePercent < 70 ? "§a" : usagePercent < 85 ? "§e" : "§c";
        
        sender.sendMessage("§6║ §eMemory Usage:");
        sender.sendMessage("§6║   " + memColor + formatBytes(used) + " §7/ §f" + formatBytes(max) + 
                         " §7(" + String.format("%.1f%%", usagePercent) + ")");
        sender.sendMessage("§6║   §7" + memBar);
    }
    
    // Utility methods
    private static String createBar(double percent) {
        int filled = (int)(percent * 10);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            bar.append(i < filled ? "§a█" : "§7░");
        }
        bar.append("§7]");
        return bar.toString();
    }
    
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
        return String.format("%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        } else {
            return secs + "s";
        }
    }
    
    private static int extractInt(String text, String prefix) {
        try {
            int start = text.indexOf(prefix) + prefix.length();
            int end = text.indexOf(",", start);
            if (end == -1) end = text.indexOf("}", start);
            return Integer.parseInt(text.substring(start, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }
    
    private static double extractDouble(String text, String prefix) {
        try {
            int start = text.indexOf(prefix) + prefix.length();
            int end = text.indexOf("ms", start);
            if (end == -1) end = text.indexOf(",", start);
            if (end == -1) end = text.indexOf("}", start);
            return Double.parseDouble(text.substring(start, end).trim());
        } catch (Exception e) {
            return 0.0;
        }
    }
}
