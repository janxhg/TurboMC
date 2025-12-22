package com.turbomc.commands;

import com.turbomc.storage.TurboStorageManager;
import com.turbomc.storage.TurboStorageHooks;
import com.turbomc.storage.integrity.ChunkIntegrityValidator;
import com.turbomc.storage.mmap.MMapReadAheadEngine;
import com.turbomc.storage.batch.ChunkBatchLoader;
import com.turbomc.storage.batch.ChunkBatchSaver;
import com.turbomc.storage.TurboRegionFileStorage;
import com.turbomc.storage.converter.RegionConverter;

import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Collections;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * /turbo storage convert <world> <to-lrf|to-mca> - Convert region format
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
                    .then(Commands.argument("world", net.minecraft.commands.arguments.DimensionArgument.dimension())
                        .then(Commands.literal("to-lrf")
                            .executes(ctx -> convertWorld(ctx, RegionConverter.FormatType.LRF)))
                        .then(Commands.literal("to-mca")
                            .executes(ctx -> convertWorld(ctx, RegionConverter.FormatType.MCA)))
                    )
                )
                .then(Commands.literal("info")
                    .executes(TurboStorageCommand::showInfo))
            )
        );
    }
    
    private static int showStats(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        
        try {
            // Get comprehensive stats from storage manager
            TurboStorageManager.StorageManagerStats stats = TurboStorageManager.getInstance().getStats();
            
            source.sendSuccess(() -> Component.literal("§6=== TurboMC Storage Statistics ==="), false);
            
            // System Status
            source.sendSuccess(() -> Component.literal("§eSystem Status:"), false);
            source.sendSuccess(() -> Component.literal("  §7Hooks Installed: " + (TurboStorageHooks.areHooksInstalled() ? "§aYes" : "§cNo")), false);
            source.sendSuccess(() -> Component.literal("  §7Active Wrappers: §a" + TurboStorageHooks.getActiveWrapperCount()), false);
            
            // Component Status
            source.sendSuccess(() -> Component.literal("§eActive Components:"), false);
            source.sendSuccess(() -> Component.literal("  §7Batch Loaders: §a" + stats.getBatchLoaders()), false);
            source.sendSuccess(() -> Component.literal("  §7Batch Savers: §a" + stats.getBatchSavers()), false);
            source.sendSuccess(() -> Component.literal("  §7MMap Engines: §a" + stats.getMmapEngines()), false);
            source.sendSuccess(() -> Component.literal("  §7Integrity Validators: §a" + stats.getIntegrityValidators()), false);
            
            // Performance Metrics
            source.sendSuccess(() -> Component.literal("§ePerformance Metrics:"), false);
            source.sendSuccess(() -> Component.literal("  §7Chunks Loaded: §a" + stats.getTotalLoaded()), false);
            source.sendSuccess(() -> Component.literal("  §7Chunks Decompressed: §a" + stats.getTotalDecompressed()), false);
            source.sendSuccess(() -> Component.literal("  §7Cache Hit Rate: §a" + String.format("%.1f%%", stats.getCacheHitRate())), false);
            source.sendSuccess(() -> Component.literal("  §7Avg Load Time: §a" + String.format("%.2fms", stats.getAvgLoadTime())), false);
            
            // Integrity Metrics
            if (stats.isIntegrityEnabled()) {
                source.sendSuccess(() -> Component.literal("§eIntegrity Validation:"), false);
                source.sendSuccess(() -> Component.literal("  §7Chunks Validated: §a" + stats.getTotalValidated()), false);
                source.sendSuccess(() -> Component.literal("  §7Corruption Rate: §c" + String.format("%.2f%%", stats.getCorruptionRate())), false);
                source.sendSuccess(() -> Component.literal("  §7Chunks Repaired: §a" + stats.getTotalRepaired()), false);
                source.sendSuccess(() -> Component.literal("  §7Checksum Storage: §a" + String.format("%.1fMB", stats.getTotalChecksumStorage() / 1024.0 / 1024.0)), false);
            }
            
            // Memory Usage
            source.sendSuccess(() -> Component.literal("§eMemory Usage:"), false);
            source.sendSuccess(() -> Component.literal("  §7MMap Memory: §a" + String.format("%.1fMB", stats.getMmapMemoryUsage() / 1024.0 / 1024.0)), false);
            
            // Feature Status
            source.sendSuccess(() -> Component.literal("§eEnabled Features:"), false);
            source.sendSuccess(() -> Component.literal("  §7Batch Operations: " + (stats.isBatchEnabled() ? "§aEnabled" : "§cDisabled")), false);
            source.sendSuccess(() -> Component.literal("  §7Memory-Mapped I/O: " + (stats.isMmapEnabled() ? "§aEnabled" : "§cDisabled")), false);
            source.sendSuccess(() -> Component.literal("  §7Integrity Validation: " + (stats.isIntegrityEnabled() ? "§aEnabled" : "§cDisabled")), false);
            
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
                Field storageField = overworld.getChunkSource().chunkMap.getClass().getDeclaredField("storage");
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
                    Field storageField = level.getChunkSource().chunkMap.getClass().getDeclaredField("storage");
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
            source.sendSuccess(() -> Component.literal("§eFinal stats: " + TurboStorageManager.getInstance().getStats().toString()), false);
            
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
            com.turbomc.storage.optimization.TurboLRFBootstrap.initialize();
            
            source.sendSuccess(() -> Component.literal("§aTurboMC storage reloaded successfully!"), false);
            source.sendSuccess(() -> Component.literal("§eNew stats: " + TurboStorageManager.getInstance().getStats().toString()), false);
            
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
                RegionFileStorage storage = getRegionStorage(level);
                if (storage == null) {
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

    private static RegionFileStorage getRegionStorage(ServerLevel level) {
         try {
            Field storageField = level.getChunkSource().chunkMap.getClass().getDeclaredField("storage");
            storageField.setAccessible(true);
            return (RegionFileStorage) storageField.get(level.getChunkSource().chunkMap);
        } catch (Exception e) {
            return null;
        }
    }
    
    // New Helper for World Conversion
    private static int convertWorld(CommandContext<CommandSourceStack> context, RegionConverter.FormatType targetFormat) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = net.minecraft.commands.arguments.DimensionArgument.getDimension(context, "world");
        
        source.sendSuccess(() -> Component.literal("§e[TurboMC] Starting conversion for world " + level.dimension().location() + " to " + targetFormat + "..."), true);
        
        // Run async to avoid blocking main thread
        CompletableFuture.runAsync(() -> {
            try {
                // Access folder via reflection
                RegionFileStorage storage = getRegionStorage(level);
                if (storage == null) {
                     source.sendFailure(Component.literal("§cCould not access storage to determine path."));
                     return;
                }

                Path regionDir = null;
                try {
                    // RegionFileStorage has 'private final Path folder;'
                    // We need to access it from RegionFileStorage class (parent of TurboRegionFileStorage/LRFRegionFileAdapter?)
                    // Actually RegionFileStorage.java (line 296) 'this.folder = folder;'
                    Class<?> clazz = storage.getClass();
                    // Walk up to find RegionFileStorage if it's a wrapper
                    while (clazz != null && clazz != RegionFileStorage.class) {
                        clazz = clazz.getSuperclass();
                    }
                    if (clazz == null) clazz = RegionFileStorage.class; // fallback

                    Field folderField = clazz.getDeclaredField("folder");
                    folderField.setAccessible(true);
                    regionDir = (Path) folderField.get(storage);
                } catch (Exception e) {
                    source.sendFailure(Component.literal("§cReflection error getting folder: " + e.getMessage()));
                    return;
                }
                
                if (!Files.exists(regionDir)) {
                     source.sendFailure(Component.literal("§cRegion directory not found: " + regionDir));
                     return;
                }

                RegionConverter converter = new RegionConverter(true);
                var result = converter.convertDirectory(regionDir, regionDir, targetFormat);
                
                source.sendSuccess(() -> Component.literal("§a[TurboMC] Conversion completed for " + level.dimension().location() + ": " + result), true);
                 source.sendSuccess(() -> Component.literal("§e[TurboMC] A restart is recommended to load new files."), true);

            } catch (Exception e) {
                source.sendFailure(Component.literal("§c[TurboMC] Conversion failed: " + e.getMessage()));
                e.printStackTrace();
            }
        });
        
        return 1;
    }
}
