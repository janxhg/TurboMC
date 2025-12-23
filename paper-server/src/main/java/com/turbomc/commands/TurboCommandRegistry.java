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
     * Create a new instance for plugin use.
     */
    public static TurboCommandRegistry create() {
        return new TurboCommandRegistry();
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
        
        // Register inspector commands
        TurboInspectorCommand.register(dispatcher);
        
        // Future commands can be registered here
        // TurboPerformanceCommand.register(dispatcher);
        // TurboConfigCommand.register(dispatcher);
        
        System.out.println("[TurboMC][Commands] Registered " + getCommandCount() + " commands");
    }
    
    /**
     * Get the number of registered commands.
     */
    public static int getCommandCount() {
        // Storage and inspector commands are registered
        return 2;
    }
    
    /**
     * Get a list of all registered command names.
     */
    public static String[] getRegisteredCommands() {
        return new String[]{
            "turbo storage",
            "turbo inspect"
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
                
                if (args.length == 0 || (!args[0].equals("storage") && !args[0].equals("inspect"))) {
                    sender.sendMessage("§6=== TurboMC Commands ===");
                    sender.sendMessage("§e/turbo storage stats §7- Show storage statistics");
                    sender.sendMessage("§e/turbo storage convert §7- Convert MCA files to LRF format");
                    sender.sendMessage("§e/turbo storage info §7- Show storage information");
                    sender.sendMessage("§e/turbo storage reload §7- Reload storage configuration");
                    sender.sendMessage("§e/turbo inspect hex <file> §7- Hex viewer for LRF files");
                    sender.sendMessage("§e/turbo inspect png <file> §7- Export region to PNG");
                    sender.sendMessage("§e/turbo inspect tree <file> §7- Chunk tree structure");
                    sender.sendMessage("§e/turbo inspect stats <file> §7- Compression statistics");
                    return true;
                }
                
                // Handle storage or inspect subcommands
                if (args.length == 1) {
                    if (args[0].equals("storage")) {
                        sendStorageUsage(sender);
                    } else if (args[0].equals("inspect")) {
                        sendInspectUsage(sender);
                    }
                    return true;
                }
                
                // Handle storage commands
                if (args[0].equals("storage")) {
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
                
                // Handle inspect commands
                if (args[0].equals("inspect")) {
                    if (args.length < 3) {
                        sendInspectUsage(sender);
                        return true;
                    }
                    
                    String inspectType = args[1].toLowerCase();
                    String fileName = args[2];
                    
                    switch (inspectType) {
                        case "hex":
                            handleInspectHex(sender, fileName);
                            return true;
                        case "png":
                            handleInspectPng(sender, fileName);
                            return true;
                        case "tree":
                            handleInspectTree(sender, fileName);
                            return true;
                        case "stats":
                            handleInspectStats(sender, fileName);
                            return true;
                        default:
                            sendInspectUsage(sender);
                            return true;
                    }
                }
                
                // Default case - should not reach here
                return false;
            }
            
            @Override
            public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                if (!sender.hasPermission("turbomc.admin")) {
                    return Arrays.asList();
                }
                
                if (args.length == 1) {
                    return Arrays.asList("storage", "inspect");
                }
                
                if (args.length == 2) {
                    if (args[0].equals("storage")) {
                        return Arrays.asList("stats", "convert", "info", "reload");
                    } else if (args[0].equals("inspect")) {
                        return Arrays.asList("hex", "png", "tree", "stats");
                    }
                }
                
                if (args.length == 3 && args[0].equals("inspect")) {
                    // Suggest common LRF files
                    return Arrays.asList("r.0.0.lrf", "r.0.-1.lrf", "r.1.0.lrf", "r.-1.0.lrf");
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
            com.turbomc.storage.optimization.TurboStorageManager.StorageManagerStats stats = com.turbomc.storage.optimization.TurboStorageHooks.getGlobalStats();
            
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
            sender.sendMessage("  §7Cache Hit Rate: §a" + String.format("%.1f%%", stats.getCacheHitRate()));
            
            sender.sendMessage("§eFeatures:");
            sender.sendMessage("  §7Batch Operations: " + (stats.isBatchEnabled() ? "§aENABLED" : "§cDISABLED"));
            sender.sendMessage("  §7Memory-Mapped I/O: " + (stats.isMmapEnabled() ? "§aENABLED" : "§cDISABLED"));
            sender.sendMessage("  §7Integrity Validation: " + (stats.isIntegrityEnabled() ? "§aENABLED" : "§cDISABLED"));
            
            // Enhanced storage information
            sender.sendMessage("§eStorage Format Analysis:");
            analyzeStorageFormats(sender);
            
            // World-specific information
            sender.sendMessage("§eWorld Storage Status:");
            analyzeWorldStorage(sender);
            
        } catch (Exception e) {
            sender.sendMessage("§cError getting storage stats: " + e.getMessage());
        }
    }
    
    private static void analyzeStorageFormats(CommandSender sender) {
        try {
            org.bukkit.Bukkit.getWorlds().forEach(world -> {
                try {
                    java.nio.file.Path worldPath = world.getWorldFolder().toPath().resolve("region");
                    if (java.nio.file.Files.exists(worldPath)) {
                        int lrfCount = 0;
                        int mcaCount = 0;
                        long lrfSize = 0;
                        long mcaSize = 0;
                        
                        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(worldPath)) {
                            java.util.List<java.nio.file.Path> regionFiles = files
                                .filter(path -> path.toString().endsWith(".lrf") || path.toString().endsWith(".mca"))
                                .toList();
                            
                            for (java.nio.file.Path file : regionFiles) {
                                long size = java.nio.file.Files.size(file);
                                if (file.toString().endsWith(".lrf")) {
                                    lrfCount++;
                                    lrfSize += size;
                                } else {
                                    mcaCount++;
                                    mcaSize += size;
                                }
                            }
                        }
                        
                        sender.sendMessage("  §7" + world.getName() + ":");
                        sender.sendMessage("    §aLRF: §b" + lrfCount + " files §7(" + formatSize(lrfSize) + ")");
                        sender.sendMessage("    §cMCA: §b" + mcaCount + " files §7(" + formatSize(mcaSize) + ")");
                        
                        if (lrfCount > 0 && mcaCount > 0) {
                            double conversionRate = (double) lrfCount / (lrfCount + mcaCount) * 100;
                            sender.sendMessage("    §7Conversion Progress: §a" + String.format("%.1f%%", conversionRate));
                        } else if (lrfCount > 0) {
                            sender.sendMessage("    §7Status: §aFully converted to LRF");
                        } else {
                            sender.sendMessage("    §7Status: §cUsing MCA format");
                        }
                    }
                } catch (Exception e) {
                    sender.sendMessage("  §c" + world.getName() + ": Error analyzing - " + e.getMessage());
                }
            });
        } catch (Exception e) {
            sender.sendMessage("  §cError analyzing storage formats: " + e.getMessage());
        }
    }
    
    private static void analyzeWorldStorage(CommandSender sender) {
        try {
            org.bukkit.Bukkit.getWorlds().forEach(world -> {
                try {
                    net.minecraft.server.level.ServerLevel serverLevel = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
                    
                    // Get chunk information
                    int loadedChunks = serverLevel.getChunkSource().getLoadedChunksCount();
                    
                    sender.sendMessage("  §7" + world.getName() + ":");
                    sender.sendMessage("    §7Loaded Chunks: §a" + loadedChunks);
                    
                    // Try to get storage type information
                    try {
                        Object chunkSource = serverLevel.getChunkSource();
                        if (chunkSource.getClass().getName().contains("Turbo")) {
                            sender.sendMessage("    §7Storage: §aTurboMC Enhanced");
                        } else {
                            sender.sendMessage("    §7Storage: §7Vanilla");
                        }
                    } catch (Exception e) {
                        sender.sendMessage("    §7Storage: §7Unknown");
                    }
                    
                } catch (Exception e) {
                    sender.sendMessage("  §c" + world.getName() + ": Error getting world info - " + e.getMessage());
                }
            });
        } catch (Exception e) {
            sender.sendMessage("  §cError analyzing world storage: " + e.getMessage());
        }
    }
    
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
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
    
    private static void sendInspectUsage(CommandSender sender) {
        sender.sendMessage("§6=== TurboMC Inspector Commands ===");
        sender.sendMessage("§e/turbo inspect hex <file> §7- Hex viewer for LRF files");
        sender.sendMessage("§e/turbo inspect png <file> §7- Export region to PNG");
        sender.sendMessage("§e/turbo inspect tree <file> §7- Chunk tree structure");
        sender.sendMessage("§e/turbo inspect stats <file> §7- Compression statistics");
        sender.sendMessage("§7Example: §e/turbo inspect hex r.0.0.lrf");
    }
    
    private static void handleInspectHex(CommandSender sender, String fileName) {
        sender.sendMessage("§6=== TurboMC Hex Viewer ===");
        sender.sendMessage("§eAnalyzing file: §f" + fileName);
        
        try {
            java.nio.file.Path filePath = java.nio.file.Paths.get("world/region/" + fileName);
            if (!java.nio.file.Files.exists(filePath)) {
                sender.sendMessage("§cFile not found: §f" + fileName);
                return;
            }
            
            com.turbomc.inspector.TurboHexViewer hexViewer = new com.turbomc.inspector.TurboHexViewer();
            com.turbomc.storage.lrf.LRFRegionFileAdapter region = new com.turbomc.storage.lrf.LRFRegionFileAdapter(null, filePath);
            
            String hexDump = hexViewer.generateHexDump(region);
            
            // Send hex dump in chunks to avoid message length limits
            String[] lines = hexDump.split("\n");
            sender.sendMessage("§aHex dump generated (" + lines.length + " lines)");
            sender.sendMessage("§7Showing first 20 lines:");
            
            for (int i = 0; i < Math.min(20, lines.length); i++) {
                sender.sendMessage("§f" + lines[i]);
            }
            
            if (lines.length > 20) {
                sender.sendMessage("§7... and " + (lines.length - 20) + " more lines");
                sender.sendMessage("§eUse console for complete output");
            }
            
        } catch (Exception e) {
            sender.sendMessage("§cError analyzing file: " + e.getMessage());
        }
    }
    
    private static void handleInspectPng(CommandSender sender, String fileName) {
        sender.sendMessage("§6=== TurboMC PNG Exporter ===");
        sender.sendMessage("§eExporting region: §f" + fileName);
        
        try {
            java.nio.file.Path filePath = java.nio.file.Paths.get("world/region/" + fileName);
            if (!java.nio.file.Files.exists(filePath)) {
                sender.sendMessage("§cFile not found: §f" + fileName);
                return;
            }
            
            com.turbomc.inspector.TurboPNGExporter pngExporter = new com.turbomc.inspector.TurboPNGExporter();
            com.turbomc.storage.lrf.LRFRegionFileAdapter region = new com.turbomc.storage.lrf.LRFRegionFileAdapter(null, filePath);
            
            String outputPath = "turbo_inspector_" + fileName.replace(".lrf", ".png");
            pngExporter.exportTopDownView(region, outputPath, 2);
            
            sender.sendMessage("§aPNG exported successfully: §f" + outputPath);
            sender.sendMessage("§eScale: 1:2, Size: 512x512 pixels");
            
        } catch (Exception e) {
            sender.sendMessage("§cError exporting PNG: " + e.getMessage());
        }
    }
    
    private static void handleInspectTree(CommandSender sender, String fileName) {
        sender.sendMessage("§6=== TurboMC Chunk Tree Viewer ===");
        sender.sendMessage("§eAnalyzing file: §f" + fileName);
        
        try {
            java.nio.file.Path filePath = java.nio.file.Paths.get("world/region/" + fileName);
            if (!java.nio.file.Files.exists(filePath)) {
                sender.sendMessage("§cFile not found: §f" + fileName);
                return;
            }
            
            com.turbomc.inspector.TurboChunkTreeView treeView = new com.turbomc.inspector.TurboChunkTreeView();
            com.turbomc.storage.lrf.LRFRegionFileAdapter region = new com.turbomc.storage.lrf.LRFRegionFileAdapter(null, filePath);
            
            java.util.Map<String, Object> tree = treeView.generateChunkTree(region);
            
            sender.sendMessage("§aChunk tree generated successfully");
            sender.sendMessage("§7Region info:");
            sender.sendMessage("§f  File: " + tree.get("region"));
            sender.sendMessage("§f  Chunks: " + ((java.util.Map<?, ?>)tree.get("region")).get("chunks"));
            sender.sendMessage("§f  Format: " + ((java.util.Map<?, ?>)tree.get("region")).get("format"));
            
            java.util.List<?> chunks = (java.util.List<?>) tree.get("chunks");
            sender.sendMessage("§f  Analyzed chunks: " + chunks.size());
            
        } catch (Exception e) {
            sender.sendMessage("§cError generating tree: " + e.getMessage());
        }
    }
    
    private static void handleInspectStats(CommandSender sender, String fileName) {
        sender.sendMessage("§6=== TurboMC Compression Statistics ===");
        sender.sendMessage("§eAnalyzing file: §f" + fileName);
        
        try {
            java.nio.file.Path filePath = java.nio.file.Paths.get("world/region/" + fileName);
            if (!java.nio.file.Files.exists(filePath)) {
                sender.sendMessage("§cFile not found: §f" + fileName);
                return;
            }
            
            com.turbomc.inspector.TurboCompressionStats stats = new com.turbomc.inspector.TurboCompressionStats();
            com.turbomc.storage.lrf.LRFRegionFileAdapter region = new com.turbomc.storage.lrf.LRFRegionFileAdapter(null, filePath);
            
            double compressionRatio = stats.calculateCompressionRatio(region);
            com.turbomc.inspector.TurboCompressionStats.CompressionStatistics detailedStats = stats.generateDetailedStats(region);
            
            sender.sendMessage("§aCompression statistics generated");
            sender.sendMessage("§7Overall compression ratio: §f" + String.format("%.2f%%", compressionRatio * 100));
            sender.sendMessage("§7Total chunks: §f" + detailedStats.totalChunks);
            sender.sendMessage("§7Total compressed size: §f" + detailedStats.totalCompressedSize + " bytes");
            sender.sendMessage("§7Total uncompressed size: §f" + detailedStats.totalUncompressedSize + " bytes");
            
            sender.sendMessage("§7Compression algorithms:");
            for (var entry : detailedStats.algorithmStats.entrySet()) {
                sender.sendMessage("§f  " + entry.getKey() + ": §a" + entry.getValue().chunkCount + " chunks");
            }
            
        } catch (Exception e) {
            sender.sendMessage("§cError generating statistics: " + e.getMessage());
        }
    }
}
