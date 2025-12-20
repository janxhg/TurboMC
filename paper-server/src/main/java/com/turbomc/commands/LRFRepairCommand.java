package com.turbomc.commands;

import com.turbomc.storage.LRFCorruptionFixer;
import com.turbomc.storage.LRFConstants;
import com.turbomc.compression.CompressionLevelValidator;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Command to repair corrupted LRF files and manage compression settings.
 * 
 * Usage:
 * /lrfrepair scan [world]     - Scan for corrupted files
 * /lrfrepair repair [world]   - Attempt to repair corrupted files
 * /lrfrepair status [world]   - Show LRF system status
 * /lrfrepair compress <safe|fast|max> - Set safe compression
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class LRFRepairCommand implements CommandExecutor {
    
    private final LRFCorruptionFixer corruptionFixer;
    
    public LRFRepairCommand() {
        this.corruptionFixer = new LRFCorruptionFixer();
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "scan":
                return handleScan(sender, args);
            case "repair":
                return handleRepair(sender, args);
            case "status":
                return handleStatus(sender, args);
            case "compress":
                return handleCompress(sender, args);
            case "help":
                sendHelp(sender);
                return true;
            default:
                sender.sendMessage("§cUnknown subcommand: " + subCommand);
                sendHelp(sender);
                return true;
        }
    }
    
    /**
     * Handle scan command - check for corruption without repairing.
     */
    private boolean handleScan(CommandSender sender, String[] args) {
        Path worldPath = getWorldPath(sender, args, 1);
        if (worldPath == null) return true;
        
        Path regionPath = worldPath.resolve("region");
        
        sender.sendMessage("§6[TurboMC] Scanning for corrupted LRF files in " + worldPath.getFileName());
        
        LRFCorruptionFixer.RepairSummary summary = corruptionFixer.repairDirectory(regionPath);
        
        sender.sendMessage("§a[TurboMC] Scan complete:");
        sender.sendMessage("§7  Valid files: §a" + summary.getValid());
        sender.sendMessage("§7  Corrupted files: §e" + summary.getCorrupted());
        sender.sendMessage("§7  Already repaired: §a" + summary.getRepaired());
        sender.sendMessage("§7  Failed repairs: §c" + summary.getFailed());
        
        if (!summary.getErrors().isEmpty()) {
            sender.sendMessage("§c[TurboMC] Errors encountered:");
            for (String error : summary.getErrors()) {
                sender.sendMessage("§c  - " + error);
            }
        }
        
        if (summary.getCorrupted() > 0) {
            sender.sendMessage("§e[TurboMC] Found corrupted files. Use §b/lrfrepair repair§e to attempt fixes.");
        }
        
        return true;
    }
    
    /**
     * Handle repair command - scan and attempt to repair corruption.
     */
    private boolean handleRepair(CommandSender sender, String[] args) {
        Path worldPath = getWorldPath(sender, args, 1);
        if (worldPath == null) return true;
        
        Path regionPath = worldPath.resolve("region");
        
        sender.sendMessage("§6[TurboMC] Scanning and repairing LRF files in " + worldPath.getFileName());
        sender.sendMessage("§7[TurboMC] Note: Automatic repair is experimental and may not recover all data.");
        
        LRFCorruptionFixer.RepairSummary summary = corruptionFixer.repairDirectory(regionPath);
        
        sender.sendMessage("§a[TurboMC] Repair operation complete:");
        sender.sendMessage("§7  Valid files: §a" + summary.getValid());
        sender.sendMessage("§7  Corrupted files found: §e" + summary.getCorrupted());
        sender.sendMessage("§7  Successfully repaired: §a" + summary.getRepaired());
        sender.sendMessage("§7  Failed repairs: §c" + summary.getFailed());
        
        if (!summary.getErrors().isEmpty()) {
            sender.sendMessage("§c[TurboMC] Errors encountered:");
            for (String error : summary.getErrors()) {
                sender.sendMessage("§c  - " + error);
            }
        }
        
        if (summary.getFailed() > 0) {
            sender.sendMessage("§c[TurboMC] Some files could not be repaired automatically.");
            sender.sendMessage("§c[TurboMC] Consider manual backup and restore, or conversion back to MCA format.");
        }
        
        return true;
    }
    
    /**
     * Handle status command - show LRF system status.
     */
    private boolean handleStatus(CommandSender sender, String[] args) {
        Path worldPath = getWorldPath(sender, args, 1);
        if (worldPath == null) return true;
        
        Path regionPath = worldPath.resolve("region");
        
        sender.sendMessage("§6[TurboMC] LRF System Status for " + worldPath.getFileName());
        
        try {
            if (!regionPath.toFile().exists()) {
                sender.sendMessage("§7  Region directory does not exist.");
                return true;
            }
            
            // Count files
            var files = java.nio.file.Files.list(regionPath)
                .filter(p -> p.toString().endsWith(".lrf") || p.toString().endsWith(".mca"))
                .toList();
            
            long lrfCount = files.stream().filter(p -> p.toString().endsWith(".lrf")).count();
            long mcaCount = files.stream().filter(p -> p.toString().endsWith(".mca")).count();
            
            sender.sendMessage("§7  LRF files: §a" + lrfCount);
            sender.sendMessage("§7  MCA files: §a" + mcaCount);
            
            if (lrfCount > 0) {
                sender.sendMessage("§7  Format: §aLRF (TurboMC Linear Region Format)");
            } else if (mcaCount > 0) {
                sender.sendMessage("§7  Format: §eMCA (Minecraft Anvil Format)");
            } else {
                sender.sendMessage("§7  Format: §cNo region files found");
            }
            
            // Check for corruption in a few sample files
            int samplesChecked = 0;
            int samplesCorrupted = 0;
            
            for (var file : files.stream().filter(p -> p.toString().endsWith(".lrf")).limit(5).toList()) {
                samplesChecked++;
                var report = corruptionFixer.detectCorruption(file);
                if (report.hasIssues()) {
                    samplesCorrupted++;
                }
            }
            
            if (samplesChecked > 0) {
                sender.sendMessage("§7  Sample corruption check: " + samplesCorrupted + "/" + samplesChecked + " files corrupted");
                if (samplesCorrupted > 0) {
                    sender.sendMessage("§e  ⚠️  Corruption detected in sample files!");
                }
            }
            
        } catch (Exception e) {
            sender.sendMessage("§c[TurboMC] Error checking status: " + e.getMessage());
        }
        
        return true;
    }
    
    /**
     * Handle compress command - set safe compression levels.
     */
    private boolean handleCompress(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /lrfrepair compress <safe|fast|max|development>");
            return true;
        }
        
        String setting = args[1].toLowerCase();
        
        CompressionLevelValidator.CompressionSettings settings;
        switch (setting) {
            case "safe":
                settings = CompressionLevelValidator.getRecommendedSettings("safe-conversion");
                break;
            case "fast":
                settings = CompressionLevelValidator.getRecommendedSettings("fast-conversion");
                break;
            case "max":
                settings = CompressionLevelValidator.getRecommendedSettings("maximum-compression");
                break;
            case "development":
                settings = CompressionLevelValidator.getRecommendedSettings("development");
                break;
            default:
                sender.sendMessage("§cUnknown setting: " + setting);
                sender.sendMessage("§7Available: safe, fast, max, development");
                return true;
        }
        
        sender.sendMessage("§6[TurboMC] Recommended compression settings for " + setting + ":");
        sender.sendMessage("§7  Algorithm: §a" + settings.algorithm);
        sender.sendMessage("§7  Level: §a" + settings.level);
        sender.sendMessage("§7  Fallback: §a" + (settings.enableFallback ? "enabled" : "disabled"));
        sender.sendMessage("§7  Description: §7" + settings.description);
        
        sender.sendMessage("");
        sender.sendMessage("§7To apply these settings, update your turbo.toml:");
        sender.sendMessage("§8[storage]");
        sender.sendMessage("§8format = \"lrf\"");
        sender.sendMessage("§8auto-convert = true");
        sender.sendMessage("§8conversion-mode = \"background\"");
        sender.sendMessage("§8compression-algorithm = \"" + settings.algorithm + "\"");
        sender.sendMessage("§8compression-level = " + settings.level);
        
        return true;
    }
    
    /**
     * Get world path from command arguments or default.
     */
    private Path getWorldPath(CommandSender sender, String[] args, int argIndex) {
        String worldName = args.length > argIndex ? args[argIndex] : "world";
        
        if (sender instanceof Player) {
            Player player = (Player) sender;
            var world = player.getWorld();
            if (worldName.equals(world.getName())) {
                return world.getWorldFolder().toPath();
            }
        }
        
        // For console or different world, try to find world folder
        Path serverPath = Paths.get(".");
        Path worldPath = serverPath.resolve(worldName);
        
        if (!worldPath.toFile().exists()) {
            sender.sendMessage("§c[TurboMC] World '" + worldName + "' not found.");
            sender.sendMessage("§7Available worlds in current directory:");
            try {
                var worlds = java.nio.file.Files.list(serverPath)
                    .filter(p -> p.toFile().isDirectory() && 
                               java.nio.file.Files.exists(p.resolve("level.dat")))
                    .map(p -> p.getFileName().toString())
                    .toList();
                
                for (String world : worlds) {
                    sender.sendMessage("§7  - " + world);
                }
            } catch (Exception e) {
                sender.sendMessage("§7  (Could not list worlds: " + e.getMessage() + ")");
            }
            return null;
        }
        
        return worldPath;
    }
    
    /**
     * Send help message to sender.
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6[TurboMC] LRF Repair Commands:");
        sender.sendMessage("§7/lrfrepair scan [world]     - Scan for corrupted LRF files");
        sender.sendMessage("§7/lrfrepair repair [world]   - Scan and repair corrupted files");
        sender.sendMessage("§7/lrfrepair status [world]   - Show LRF system status");
        sender.sendMessage("§7/lrfrepair compress <type>  - Show safe compression settings");
        sender.sendMessage("§7/lrfrepair help             - Show this help");
        sender.sendMessage("");
        sender.sendMessage("§7Compression types:");
        sender.sendMessage("§8  safe      - Safe for conversion (LZ4 level 6)");
        sender.sendMessage("§8  fast      - Fast conversion (LZ4 level 3)");
        sender.sendMessage("§8  max       - Maximum safe compression (ZSTD level 15)");
        sender.sendMessage("§8  development - Fastest for development (LZ4 level 1)");
    }
}
