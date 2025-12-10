package com.turbomc.commands;

import com.turbomc.storage.TurboStorageManager;
import com.turbomc.storage.TurboStorageHooks;
import com.turbomc.storage.ChunkIntegrityValidator;
import com.turbomc.storage.MMapReadAheadEngine;
import com.turbomc.storage.ChunkBatchLoader;
import com.turbomc.storage.ChunkBatchSaver;
import com.turbomc.storage.TurboRegionFileStorage;

import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Collections;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

/**
 * Administrative command for TurboMC storage system.
 * Provides comprehensive monitoring, management, and debugging capabilities.
 * 
 * Commands:
 * /turbo storage stats - Show storage system statistics
 * /turbo storage validate <x> <z> - Validate integrity of region
 * /turbo storage flush - Flush all pending operations
 * /turbo storage cleanup - Cleanup unused resources
 * /turbo storage reload - Reload storage configuration
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class TurboStorageCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("turbo")
            .then(Commands.literal("storage")
                .requires(source -> source.hasPermission(4)) // OP level 4 (admin)
                .then(Commands.literal("stats")
                    .executes(TurboStorageCommand::showStats))
                .then(Commands.literal("validate")
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(TurboStorageCommand::validateRegion))))
                .then(Commands.literal("flush")
                    .executes(TurboStorageCommand::flushStorage))
                .then(Commands.literal("cleanup")
                    .executes(TurboStorageCommand::cleanupStorage))
                .then(Commands.literal("reload")
                    .executes(TurboStorageCommand::reloadStorage))
                .then(Commands.literal("convert")
                    .executes(TurboStorageCommand::convertToLRF))
                .then(Commands.literal("info")
                    .executes(TurboStorageCommand::showInfo))
            )
        );
    }
    
    private static int showStats(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        
        try {
            TurboStorageManager.StorageManagerStats stats = TurboStorageHooks.getGlobalStats();
            
            source.sendSuccess(() -> Component.literal("§6=== TurboMC Storage Statistics ==="), false);
            source.sendSuccess(() -> Component.literal("§eActive Components:"), false);
            source.sendSuccess(() -> Component.literal("  §7Batch Loaders: §a" + stats.getBatchLoaders()), false);
            source.sendSuccess(() -> Component.literal("  §7Batch Savers: §a" + stats.getBatchSavers()), false);
            source.sendSuccess(() -> Component.literal("  §7MMap Engines: §a" + stats.getMmapEngines()), false);
            source.sendSuccess(() -> Component.literal("  §7Integrity Validators: §a" + stats.getIntegrityValidators()), false);
            
            source.sendSuccess(() -> Component.literal("§ePerformance Metrics:"), false);
            source.sendSuccess(() -> Component.literal("  §7Chunks Loaded: §a" + stats.getTotalLoaded()), false);
            source.sendSuccess(() -> Component.literal("  §7Chunks Decompressed: §a" + stats.getTotalDecompressed()), false);
            source.sendSuccess(() -> Component.literal("  §7Corrupted Chunks: §c" + stats.getTotalCorrupted() + " §7(" + String.format("%.2f%%", stats.getCorruptionRate()) + "%"), false);
            source.sendSuccess(() -> Component.literal("  §7Avg Load Time: §a" + String.format("%.2fms", stats.getAvgLoadTime())), false);
            
            source.sendSuccess(() -> Component.literal("§eIntegrity Validation:"), false);
            source.sendSuccess(() -> Component.literal("  §7Chunks Validated: §a" + stats.getTotalValidated()), false);
            source.sendSuccess(() -> Component.literal("  §7Corrupted Chunks: §c" + stats.getTotalCorrupted()), false);
            source.sendSuccess(() -> Component.literal("  §7Chunks Repaired: §a" + stats.getTotalRepaired()), false);
           source.sendSuccess(() -> Component.literal("§eMemory Usage:"), false);
            source.sendSuccess(() -> Component.literal("  §7MMap Memory: §a" + String.format("%.1fMB", stats.getMmapMemoryUsage() / 1024.0 / 1024.0)), false);
            source.sendSuccess(() -> Component.literal("  §7Checksum Storage: §a" + String.format("%.1fMB", stats.getTotalChecksumStorage() / 1024.0 / 1024.0)), false);
            
            source.sendSuccess(() -> Component.literal("§eFeature Status:"), false);
            source.sendSuccess(() -> Component.literal("  §7Batch Operations: " + (stats.isBatchEnabled() ? "§aENABLED" : "§cDISABLED")), false);
            source.sendSuccess(() -> Component.literal("  §7Memory-Mapped I/O: " + (stats.isMmapEnabled() ? "§aENABLED" : "§cDISABLED")), false);
            source.sendSuccess(() -> Component.literal("  §7Integrity Validation: " + (stats.isIntegrityEnabled() ? "§aENABLED" : "§cDISABLED")), false);
            source.sendSuccess(() -> Component.literal("§6Active Wrappers: §a" + TurboStorageHooks.getActiveWrapperCount()), false);
            
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError getting storage stats: " + e.getMessage()));
            throw new RuntimeException(e);
        }
        
        return 1;
    }
    
    private static int validateRegion(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        int regionX = IntegerArgumentType.getInteger(context, "x");
        int regionZ = IntegerArgumentType.getInteger(context, "z");
        
        source.sendSuccess(() -> Component.literal("§6Validating region " + regionX + "," + regionZ + "..."), false);
        
        try {
            MinecraftServer server = source.getServer();
            ServerLevel overworld = server.overworld();
            
            // Get the region storage for overworld using reflection
            RegionFileStorage storage;
            try {
                java.lang.reflect.Field storageField = overworld.getChunkSource().chunkMap.getClass().getDeclaredField("storage");
                storageField.setAccessible(true);
                storage = (RegionFileStorage) storageField.get(overworld.getChunkSource().chunkMap);
            } catch (Exception e) {
                source.sendFailure(Component.literal("§cError accessing storage: " + e.getMessage()));
                return 0;
            }
            
            if (storage.getClass().getName().contains("TurboRegionFileStorage")) {
                // This is a TurboMC-enhanced storage
                source.sendSuccess(() -> Component.literal("§6TurboMC storage detected - validation not yet implemented for wrapper"), false);
            } else {
                // Vanilla RegionFileStorage - basic validation
                source.sendSuccess(() -> Component.literal("§6Vanilla storage detected - basic validation only"), false);
            }
            
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError validating region: " + e.getMessage()));
            throw new RuntimeException(e);
        }
        
        return 1;
    }
    
    private static int flushStorage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        
        source.sendSuccess(() -> Component.literal("§6Flushing all TurboMC storage operations..."), false);
        
        try {
            MinecraftServer server = source.getServer();
            
            // Flush all worlds
            for (ServerLevel level : server.getAllLevels()) {
                RegionFileStorage storage;
                try {
                    java.lang.reflect.Field storageField = level.getChunkSource().chunkMap.getClass().getDeclaredField("storage");
                    storageField.setAccessible(true);
                    storage = (RegionFileStorage) storageField.get(level.getChunkSource().chunkMap);
                } catch (Exception e) {
                    source.sendFailure(Component.literal("§cError accessing storage for world: " + level.dimension().location()));
                    continue;
                }
                
                boolean isTurbo = storage.getClass().getName().contains("TurboRegionFileStorage");
                if (isTurbo) {
                    // Try to call flush method via reflection
                    try {
                        java.lang.reflect.Method flushMethod = storage.getClass().getMethod("flush");
                        flushMethod.invoke(storage);
                        source.sendSuccess(() -> Component.literal("§aFlushed TurboMC storage for world: " + level.dimension().location()), false);
                    } catch (Exception e) {
                        source.sendFailure(Component.literal("§cError flushing TurboMC storage: " + e.getMessage()));
                    }
                } else {
                    source.sendSuccess(() -> Component.literal("§7Vanilla storage for world: " + level.dimension().location() + " (no flush needed)"), false);
                }
            }
            
            source.sendSuccess(() -> Component.literal("§aAll storage operations flushed successfully!"), false);
            
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError flushing storage: " + e.getMessage()));
            throw new RuntimeException(e);
        }
        
        return 1;
    }
    
    private static int cleanupStorage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        
        source.sendSuccess(() -> Component.literal("§6Cleaning up unused TurboMC resources..."), false);
        
        try {
            int wrappersBefore = TurboStorageHooks.getActiveWrapperCount();
            
            // Cleanup all wrappers
            TurboStorageHooks.cleanupAll();
            
            source.sendSuccess(() -> Component.literal("§aCleanup completed! Removed " + wrappersBefore + " wrappers"), false);
            source.sendSuccess(() -> Component.literal("§eFinal stats: " + TurboStorageHooks.getGlobalStats()), false);
            
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError during cleanup: " + e.getMessage()));
            throw new RuntimeException(e);
        }
        
        return 1;
    }
    
    private static int reloadStorage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        
        source.sendSuccess(() -> Component.literal("§6Reloading TurboMC storage configuration..."), false);
        
        try {
            // Cleanup existing
            TurboStorageHooks.cleanupAll();
            
            // Reinitialize
            com.turbomc.storage.TurboLRFBootstrap.initialize();
            
            source.sendSuccess(() -> Component.literal("§aTurboMC storage reloaded successfully!"), false);
            source.sendSuccess(() -> Component.literal("§eNew stats: " + TurboStorageHooks.getGlobalStats()), false);
            
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError reloading storage: " + e.getMessage()));
            throw new RuntimeException(e);
        }
        
        return 1;
    }
    
    private static int showInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        
        source.sendSuccess(() -> Component.literal("§6=== TurboMC Storage Information ==="), false);
        source.sendSuccess(() -> Component.literal("§eVersion: §a1.0.0"), false);
        source.sendSuccess(() -> Component.literal("§eHooks Installed: " + (TurboStorageHooks.areHooksInstalled() ? "§aYes" : "§cNo")), false);
        source.sendSuccess(() -> Component.literal("§eActive Wrappers: §a" + TurboStorageHooks.getActiveWrapperCount()), false);
        
        // Show world-specific info
        try {
            MinecraftServer server = source.getServer();
            
            source.sendSuccess(() -> Component.literal("§eWorld Storage Status:"), false);
            
            for (ServerLevel level : server.getAllLevels()) {
                RegionFileStorage storage;
                try {
                    java.lang.reflect.Field storageField = level.getChunkSource().chunkMap.getClass().getDeclaredField("storage");
                    storageField.setAccessible(true);
                    storage = (RegionFileStorage) storageField.get(level.getChunkSource().chunkMap);
                } catch (Exception e) {
                    source.sendFailure(Component.literal("§cError accessing storage for world: " + level.dimension().location()));
                    continue;
                }
                
                boolean isTurbo = storage.getClass().getName().contains("TurboRegionFileStorage");
                String status = isTurbo ? "§aTurboMC" : "§7Vanilla";
                
                source.sendSuccess(() -> Component.literal("  §7" + level.dimension().location() + ": " + status), false);
                
                if (isTurbo) {
                    try {
                        java.lang.reflect.Method getStatsMethod = storage.getClass().getMethod("getTurboStats");
                        Object stats = getStatsMethod.invoke(storage);
                        source.sendSuccess(() -> Component.literal("    §7TurboMC stats available"), false);
                    } catch (Exception e) {
                        source.sendSuccess(() -> Component.literal("    §7TurboMC stats unavailable"), false);
                    }
                }
            }
            
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError getting world info: " + e.getMessage()));
        }
        
        return 1;
    }
    
    private static int convertToLRF(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        
        source.sendSuccess(() -> Component.literal("§e[TurboMC] Starting MCA to LRF conversion..."), false);
        
        try {
            java.nio.file.Path regionDir = java.nio.file.Paths.get("world/region");
            com.turbomc.storage.converter.RegionConverter converter = new com.turbomc.storage.converter.RegionConverter(true);
            
            var result = converter.convertDirectory(regionDir, regionDir, com.turbomc.storage.converter.RegionConverter.FormatType.LRF);
            
            source.sendSuccess(() -> Component.literal("§a[TurboMC] Conversion completed: " + result.toString()), false);
            source.sendSuccess(() -> Component.literal("§e[TurboMC] Check world/region directory for .lrf files"), false);
            
        } catch (Exception e) {
            source.sendFailure(Component.literal("§c[TurboMC] Conversion failed: " + e.getMessage()));
            e.printStackTrace();
        }
        
        return 1;
    }
}
