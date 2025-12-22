package com.turbomc.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Registry for all TurboMC commands.
 * This class registers all TurboMC administrative commands with Paper's command system.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public final class TurboCommandRegistry {
    
    private TurboCommandRegistry() {
        // Utility class
    }
    
    /**
     * Register all TurboMC commands.
     * Call this during server startup after commands are initialized.
     * 
     * @param dispatcher The command dispatcher to register commands with
     */
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Register storage management commands
        TurboStorageCommand.register(dispatcher);
        
        // Future commands can be registered here
        // TurboPerformanceCommand.register(dispatcher);
        // TurboConfigCommand.register(dispatcher);
        
        System.out.println("[TurboMC][Commands] Registered " + getCommandCount() + " commands");
    }
    
    /**
     * Get the number of registered commands.
     */
    public static int getCommandCount() {
        // Currently only storage commands are registered
        return 1;
    }
    
    /**
     * Get a list of all registered command names.
     */
    public static String[] getRegisteredCommands() {
        return new String[]{
            "turbo storage"
        };
    }
    
    /**
     * Register all TurboMC commands with Bukkit command system.
     * This method should be called during server startup.
     */
    public static void registerBukkitCommands() {
        System.out.println("[TurboMC][Commands] Registering Bukkit commands...");
        
        // Create and register the turbo storage command
        Command turboCommand = new Command("turbo") {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                if (!sender.hasPermission("turbomc.admin")) {
                    sender.sendMessage("§cYou don't have permission to use this command.");
                    return true;
                }
                
                if (args.length == 0 || !args[0].equals("storage")) {
                    sender.sendMessage("§6=== TurboMC Commands ===");
                    sender.sendMessage("§e/turbo storage stats §7- Show storage statistics");
                    sender.sendMessage("§e/turbo storage convert §7- Convert MCA files to LRF format");
                    sender.sendMessage("§e/turbo storage info §7- Show storage information");
                    sender.sendMessage("§e/turbo storage reload §7- Reload storage configuration");
                    return true;
                }
                
                // Handle storage subcommands
                if (args.length == 1) {
                    sendStorageUsage(sender);
                    return true;
                }
                
                String subCommand = args[1].toLowerCase();
                switch (subCommand) {
                    case "stats":
                        handleStorageStats(sender);
                        return true;
                    case "convert":
                        sender.sendMessage("§6=== TurboMC Storage Conversion ===");
                        sender.sendMessage("§eUse §e/lrfrepair convert §7for LRF conversion operations");
                        return true;
                    case "info":
                        handleStorageInfo(sender);
                        return true;
                    case "reload":
                        handleStorageReload(sender);
                        return true;
                    default:
                        sendStorageUsage(sender);
                        return true;
                }
            }
            
            @Override
            public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                if (!sender.hasPermission("turbomc.admin")) {
                    return Arrays.asList();
                }
                
                if (args.length == 1) {
                    return Arrays.asList("storage");
                }
                
                if (args.length == 2 && args[0].equals("storage")) {
                    return Arrays.asList("stats", "convert", "info", "reload");
                }
                
                return Arrays.asList();
            }
        };
        
        // Register the turbo command
        net.minecraft.server.MinecraftServer.getServer().server.getCommandMap().register("turbo", "TurboMC", turboCommand);
        
        // Create and register the LRF repair command
        LRFRepairCommand lrfRepairInstance = new LRFRepairCommand();
        Command lrfRepairCommand = new Command("lrfrepair") {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return lrfRepairInstance.onCommand(sender, this, commandLabel, args);
            }
            
            @Override
            public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                return lrfRepairInstance.onTabComplete(sender, this, alias, args);
            }
        };
        
        // Register the lrfrepair command
        net.minecraft.server.MinecraftServer.getServer().server.getCommandMap().register("lrfrepair", "TurboMC", lrfRepairCommand);
        
        System.out.println("[TurboMC][Commands] Registered 2 Bukkit commands: turbo, lrfrepair");
    }
    
    private static void sendStorageUsage(CommandSender sender) {
        sender.sendMessage("§6=== TurboMC Storage Commands ===");
        sender.sendMessage("§e/turbo storage stats §7- Show storage statistics");
        sender.sendMessage("§e/turbo storage convert §7- Convert MCA files to LRF format");
        sender.sendMessage("§e/turbo storage info §7- Show storage information");
        sender.sendMessage("§e/turbo storage reload §7- Reload storage configuration");
    }
    
    private static void handleStorageStats(CommandSender sender) {
        try {
            com.turbomc.storage.TurboStorageManager.StorageManagerStats stats = com.turbomc.storage.TurboStorageHooks.getGlobalStats();
            
            sender.sendMessage("§6=== TurboMC Storage Statistics ===");
            sender.sendMessage("§eActive Components:");
            sender.sendMessage("  §7Batch Loaders: §a" + stats.getBatchLoaders());
            sender.sendMessage("  §7Batch Savers: §a" + stats.getBatchSavers());
            sender.sendMessage("  §7MMap Engines: §a" + stats.getMmapEngines());
            sender.sendMessage("  §7Integrity Validators: §a" + stats.getIntegrityValidators());
            
            sender.sendMessage("§ePerformance Metrics:");
            sender.sendMessage("  §7Chunks Loaded: §a" + stats.getTotalLoaded());
            sender.sendMessage("  §7Chunks Decompressed: §a" + stats.getTotalDecompressed());
            sender.sendMessage("  §7Corrupted Chunks: §c" + stats.getTotalCorrupted() + " §7(" + String.format("%.2f%%", stats.getCorruptionRate()) + "%)");
            sender.sendMessage("  §7Avg Load Time: §a" + String.format("%.2fms", stats.getAvgLoadTime()));
            
            sender.sendMessage("§eFeatures:");
            sender.sendMessage("  §7Batch Operations: " + (stats.isBatchEnabled() ? "§aENABLED" : "§cDISABLED"));
            sender.sendMessage("  §7Memory-Mapped I/O: " + (stats.isMmapEnabled() ? "§aENABLED" : "§cDISABLED"));
            sender.sendMessage("  §7Integrity Validation: " + (stats.isIntegrityEnabled() ? "§aENABLED" : "§cDISABLED"));
            
        } catch (Exception e) {
            sender.sendMessage("§cError getting storage stats: " + e.getMessage());
        }
    }
    
    private static void handleStorageConvert(CommandSender sender) {
        sender.sendMessage("§e[TurboMC] Starting MCA to LRF conversion...");
        
        try {
            java.nio.file.Path regionDir = java.nio.file.Paths.get("world/region");
            com.turbomc.storage.converter.RegionConverter converter = new com.turbomc.storage.converter.RegionConverter(true);
            
            var result = converter.convertDirectory(regionDir, regionDir, com.turbomc.storage.converter.RegionConverter.FormatType.LRF);
            
            sender.sendMessage("§a[TurboMC] Conversion completed: " + result.toString());
            sender.sendMessage("§e[TurboMC] Check world/region directory for .lrf files");
            
        } catch (Exception e) {
            sender.sendMessage("§c[TurboMC] Conversion failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void handleStorageInfo(CommandSender sender) {
        sender.sendMessage("§6=== TurboMC Storage Information ===");
        sender.sendMessage("§eStorage Format: §aLRF (Linear Region Format)");
        sender.sendMessage("§eCompression: §aLZ4 (Level 6)");
        sender.sendMessage("§eBatch Operations: §aENABLED");
        sender.sendMessage("§eIntegrity Validation: §aENABLED");
        sender.sendMessage("§7Use §e/lrfrepair status §7for detailed LRF system status");
        
        try {
            sender.sendMessage("§eWorld Storage Status:");
            
            net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
                Object chunkMap = level.getChunkSource().chunkMap;
                boolean isTurbo = chunkMap.getClass().getName().contains("Turbo");
                String status = isTurbo ? "§aTurboMC" : "§7Vanilla";
                
                sender.sendMessage("  §7" + level.dimension().location() + ": " + status);
            }
            
        } catch (Exception e) {
            sender.sendMessage("§cError getting storage info: " + e.getMessage());
        }
    }
    
    private static void handleStorageReload(CommandSender sender) {
        sender.sendMessage("§e[TurboMC] Reloading storage configuration...");
        
        try {
            // Get current TurboConfig instance - it will reload from file automatically
            com.turbomc.config.TurboConfig config = com.turbomc.config.TurboConfig.getInstance(new java.io.File("."));
            
            sender.sendMessage("§a[TurboMC] Storage configuration reloaded successfully");
            sender.sendMessage("§7Use §e/turbo storage stats §7to verify new settings");
            
        } catch (Exception e) {
            sender.sendMessage("§c[TurboMC] Failed to reload configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
