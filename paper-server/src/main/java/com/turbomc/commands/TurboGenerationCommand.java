package com.turbomc.commands;

import org.bukkit.command.CommandSender;
import com.turbomc.world.TurboWorldManager;
import com.turbomc.world.ParallelChunkGenerator;
import com.turbomc.config.TurboConfig;

/**
 * Command for managing parallel chunk generation (v2.2.0).
 * Allows viewing stats, toggling, and inspecting the generation queue.
 * 
 * @author TurboMC
 * @version 2.3.0
 */
public class TurboGenerationCommand {
    
    public static void showStats(CommandSender sender) {
        sender.sendMessage("§6╔═══════════════════════════════════════════╗");
        sender.sendMessage("§6║  §e§lParallel Generation Stats (v2.2.0)§6    ║");
        sender.sendMessage("§6╠═══════════════════════════════════════════╣");
        
        try {
            TurboWorldManager worldMgr = TurboWorldManager.getInstance();
            String stats = worldMgr.getStats();
            
            if (stats.contains("No active generators")) {
                sender.sendMessage("§6║ §7Status: §eIdle");
                sender.sendMessage("§6║ §7No active generation tasks");
            } else {
                // Parse and display detailed stats
                String[] lines = stats.split("\n");
                for (String line : lines) {
                    if (line.contains("Generator Stats:")) {
                        sender.sendMessage("§6║ §eActive Generators:");
                    } else if (line.trim().startsWith("minecraft:")) {
                        // World name
                        String world = line.substring(line.indexOf("minecraft:"), line.indexOf(":"));
                        sender.sendMessage("§6║   §7World: §a" + world);
                    } else if (line.contains("ParallelGen{")) {
                        parseAndDisplay(sender, line);
                    }
                }
            }
            
            // Configuration
            TurboConfig config = TurboConfig.getInstance();
            sender.sendMessage("§6╟───────────────────────────────────────────╢");
            sender.sendMessage("§6║ §eConfiguration:");
            sender.sendMessage("§6║   §7Enabled: " + (config.getBoolean("world.generation.parallel-enabled", true) ? "§aYES" : "§cNO"));
            sender.sendMessage("§6║   §7Worker Threads: §a" + config.getInt("world.generation.generation-threads", 0));
            sender.sendMessage("§6║   §7Max Concurrent: §a" + config.getInt("world.generation.max-concurrent-generations", 16));
            sender.sendMessage("§6║   §7Priority Mode: §e" + config.getString("world.generation.priority-mode", "direction"));
            sender.sendMessage("§6║   §7Lookahead Distance: §a" + config.getInt("world.generation.pregeneration-distance", 24) + " §7chunks");
            
            sender.sendMessage("§6╚═══════════════════════════════════════════╝");
            
        } catch (Exception e) {
            sender.sendMessage("§6║ §cError: " + e.getMessage());
            sender.sendMessage("§6╚═══════════════════════════════════════════╝");
        }
    }
    
    private static void parseAndDisplay(CommandSender sender, String line) {
        try {
            // Extract values
            int generated = extractInt(line, "generated=");
            int pregenerated = extractInt(line, "pregenerated=");
            double avgTime = extractDouble(line, "avgTime=");
            int pending = extractInt(line, "pending=");
            int queued = extractInt(line, "queued=");
            long uptime = extractLong(line, "uptime=");
            
            sender.sendMessage("§6║   §7Generated: §a" + generated + " §7chunks");
            sender.sendMessage("§6║   §7Pregenerated: §e" + pregenerated + " §7chunks");
            
            String timeColor = avgTime < 50 ? "§a" : avgTime < 100 ? "§e" : "§c";
            sender.sendMessage("§6║   §7Avg Time: " + timeColor + String.format("%.1fms", avgTime));
            
            sender.sendMessage("§6║   §7Pending: §e" + pending);
            sender.sendMessage("§6║   §7Queued: §e" + queued);
            sender.sendMessage("§6║   §7Uptime: §f" + formatUptime(uptime));
            
        } catch (Exception e) {
            sender.sendMessage("§6║   §7Raw: " + line.substring(line.indexOf("ParallelGen{")));
        }
    }
    
    public static void toggle(CommandSender sender, boolean enable) {
        sender.sendMessage("§6[TurboMC] Parallel generation " + (enable ? "§aenabled" : "§cdisabled"));
        sender.sendMessage("§7Note: This requires server restart to take full effect.");
        sender.sendMessage("§7Edit §eturbo.toml§7: §eworld.generation.parallel-enabled = " + enable);
    }
    
    public static void showQueue(CommandSender sender) {
        sender.sendMessage("§6╔═══════════════════════════════════════════╗");
        sender.sendMessage("§6║  §e§lGeneration Queue§6                      ║");
        sender.sendMessage("§6╠═══════════════════════════════════════════╣");
        
        try {
            TurboWorldManager worldMgr = TurboWorldManager.getInstance();
            String stats = worldMgr.getStats();
            
            if (stats.contains("No active generators")) {
                sender.sendMessage("§6║ §7Queue is empty");
            } else {
                // Extract queue size from stats
                String[] lines = stats.split("\n");
                for (String line : lines) {
                    if (line.contains("queued=")) {
                        int queued = extractInt(line, "queued=");
                        int pending = extractInt(line, "pending=");
                        
                        sender.sendMessage("§6║ §7Pending Tasks: §e" + pending);
                        sender.sendMessage("§6║ §7Queued Tasks: §e" + queued);
                        sender.sendMessage("§6║ §7Total: §a" + (pending + queued));
                    }
                }
            }
            
            sender.sendMessage("§6╚═══════════════════════════════════════════╝");
        } catch (Exception e) {
            sender.sendMessage("§6║ §cError: " + e.getMessage());
            sender.sendMessage("§6╚═══════════════════════════════════════════╝");
        }
    }
    
    // Utility methods
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
    
    private static long extractLong(String text, String prefix) {
        try {
            int start = text.indexOf(prefix) + prefix.length();
            int end = text.indexOf("s", start);
            if (end == -1) end = text.indexOf("}", start);
            return Long.parseLong(text.substring(start, end).trim());
        } catch (Exception e) {
            return 0L;
        }
    }
    
    private static String formatUptime(long seconds) {
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
}
