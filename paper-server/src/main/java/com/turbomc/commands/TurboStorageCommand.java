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
            
            if (storage instanceof TurboRegionFileStorage) {
                TurboRegionFileStorage turboStorage = (TurboRegionFileStorage) storage;
                
                CompletableFuture<List<ChunkIntegrityValidator.IntegrityReport>> future = 
                    turboStorage.validateRegion(regionX, regionZ);
                
                future.thenAccept(reports -> {
                    source.sendSuccess(() -> Component.literal("§6Validation completed for region " + regionX + "," + regionZ), false);
                    
                    int valid = 0, corrupted = 0, repaired = 0, missing = 0;
                    
                    for (ChunkIntegrityValidator.IntegrityReport report : reports) {
                        switch (report.getResult()) {
                            case VALID:
                                valid++;
                                break;
                            case CORRUPTED:
                                corrupted++;
                                break;
                            case REPAIRED:
                                repaired++;
                                break;
                            case MISSING:
                                missing++;
                                break;
                        }
                    }
                    
                    final int validFinal = valid;
                    final int corruptedFinal = corrupted;
                    final int repairedFinal = repaired;
                    final int missingFinal = missing;
                    
                    source.sendSuccess(() -> Component.literal("§eValidation Results:"), false);
                    source.sendSuccess(() -> Component.literal("  §7Valid Chunks: §a" + validFinal), false);
                    source.sendSuccess(() -> Component.literal("  §7Corrupted Chunks: §c" + corruptedFinal), false);
                    source.sendSuccess(() -> Component.literal("  §7Repaired Chunks: §e" + repairedFinal), false);
                    source.sendSuccess(() -> Component.literal("  §7Missing Chunks: §7" + missingFinal), false);
                    
                    if (corruptedFinal > 0) {
                        source.sendSuccess(() -> Component.literal("§cWarning: " + corruptedFinal + " chunks are corrupted!"), false);
                    }
                    
                    if (repairedFinal > 0) {
                        source.sendSuccess(() -> Component.literal("§aGood: " + repairedFinal + " chunks were automatically repaired!"), false);
                    }
                }).exceptionally(throwable -> {
                    source.sendFailure(Component.literal("§cError validating region: " + throwable.getMessage()));
                    return null;
                });
                
            } else {
                source.sendFailure(Component.literal("§cTurboMC storage is not active for this world"));
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
                
                if (storage instanceof TurboRegionFileStorage) {
                    TurboRegionFileStorage turboStorage = (TurboRegionFileStorage) storage;
                    turboStorage.flush();
                    source.sendSuccess(() -> Component.literal("§aFlushed storage for world: " + level.dimension().location()), false);
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
                
                boolean isTurbo = storage instanceof TurboRegionFileStorage;
                String status = isTurbo ? "§aTurboMC" : "§7Vanilla";
                
                source.sendSuccess(() -> Component.literal("  §7" + level.dimension().location() + ": " + status), false);
                
                if (isTurbo) {
                    TurboRegionFileStorage turboStorage = (TurboRegionFileStorage) storage;
                    TurboStorageManager.StorageManagerStats stats = turboStorage.getTurboStats();
                    source.sendSuccess(() -> Component.literal("    §7Cache Hit Rate: §a" + String.format("%.1f%%", stats.getCacheHitRate())), false);
                }
            }
            
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cError getting world info: " + e.getMessage()));
        }
        
        return 1;
    }
}
