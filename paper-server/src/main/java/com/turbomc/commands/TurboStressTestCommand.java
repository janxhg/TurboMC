package com.turbomc.commands;

import com.turbomc.config.TurboConfig;
import com.turbomc.core.autopilot.TurboHardwareProfiler;
import com.turbomc.core.autopilot.TurboDynamicConfig;
import com.turbomc.storage.optimization.TurboStorageManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.World;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

/**
 * Advanced stress testing command with Fase 5 Dynamic Configuration integration.
 * Provides comprehensive performance testing with hardware-aware optimization.
 * 
 * @author TurboMC
 * @version 2.4.0 - Fase 5 Enhanced
 */
public class TurboStressTestCommand {
    
    // Test configuration
    private static class TestConfig {
        int entityCount;
        String entityType;
        int chunkCount;
        int radius;
        boolean enableMaxAI;
        int duration;
        
        TestConfig(int entityCount, String entityType, int chunkCount, int radius, boolean enableMaxAI, int duration) {
            this.entityCount = entityCount;
            this.entityType = entityType;
            this.chunkCount = chunkCount;
            this.radius = radius;
            this.enableMaxAI = enableMaxAI;
            this.duration = duration;
        }
    }
    
    // Performance metrics
    private static class PerformanceMetrics {
        AtomicLong totalTPS = new AtomicLong(0);
        AtomicLong totalMSPT = new AtomicLong(0);
        AtomicLong memoryUsed = new AtomicLong(0);
        AtomicInteger lagSpikes = new AtomicInteger(0);
        long startTime;
        int samples;
        
        void reset() {
            totalTPS.set(0);
            totalMSPT.set(0);
            memoryUsed.set(0);
            lagSpikes.set(0);
            startTime = System.currentTimeMillis();
            samples = 0;
        }
        
        void addSample(double tps, double mspt, long memory) {
            totalTPS.addAndGet((long)(tps * 100));
            totalMSPT.addAndGet((long)(mspt * 100));
            memoryUsed.addAndGet(memory);
            samples++;
            
            if (mspt > 50) {
                lagSpikes.incrementAndGet();
            }
        }
        
        double getAverageTPS() {
            return samples > 0 ? (totalTPS.get() / 100.0) / samples : 0;
        }
        
        double getAverageMSPT() {
            return samples > 0 ? (totalMSPT.get() / 100.0) / samples : 0;
        }
        
        long getAverageMemory() {
            return samples > 0 ? memoryUsed.get() / samples : 0;
        }
    }
    
    // Entity activation configuration for maximum AI processing
    private static class EntityActivationConfig {
        int monsterRange;
        int animalRange;
        int wakeUpMonsters;
        int wakeUpInterval;
        
        static EntityActivationConfig createMaxAI() {
            EntityActivationConfig config = new EntityActivationConfig();
            config.monsterRange = 8;    // Minimum range for constant activation
            config.animalRange = 8;
            config.wakeUpMonsters = 1000;  // Wake up all monsters per tick
            config.wakeUpInterval = 1;     // Every tick
            return config;
        }
        
        void apply() {
            // Apply to Spigot configuration
            try {
                org.spigotmc.SpigotWorldConfig spigotConfig = 
                    ((org.bukkit.craftbukkit.CraftWorld)org.bukkit.Bukkit.getWorlds().get(0)).getHandle().spigotConfig;
                
                spigotConfig.monsterActivationRange = monsterRange;
                spigotConfig.animalActivationRange = animalRange;
                spigotConfig.wakeUpInactiveMonsters = wakeUpMonsters;
                spigotConfig.wakeUpInactiveMonstersEvery = wakeUpInterval;
                
            } catch (Exception e) {
                System.err.println("[TurboStressTest] Failed to apply entity activation config: " + e.getMessage());
            }
        }
        
        void restore() {
            // Restore default values
            try {
                org.spigotmc.SpigotWorldConfig spigotConfig = 
                    ((org.bukkit.craftbukkit.CraftWorld)org.bukkit.Bukkit.getWorlds().get(0)).getHandle().spigotConfig;
                
                spigotConfig.monsterActivationRange = 32;  // Default
                spigotConfig.animalActivationRange = 32;
                spigotConfig.wakeUpInactiveMonsters = 8;
                spigotConfig.wakeUpInactiveMonstersEvery = 400;
                
            } catch (Exception e) {
                System.err.println("[TurboStressTest] Failed to restore entity activation config: " + e.getMessage());
            }
        }
    }
    
    public static void register(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        dispatcher.register(net.minecraft.commands.Commands.literal("turbo")
            .then(net.minecraft.commands.Commands.literal("stress")
                .requires(source -> source.hasPermission(2, "turbomc.admin.stress"))
                .then(net.minecraft.commands.Commands.literal("mobs")
                    .then(net.minecraft.commands.Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 5000))
                        .then(net.minecraft.commands.Commands.argument("type", com.mojang.brigadier.arguments.StringArgumentType.string())
                            .then(net.minecraft.commands.Commands.argument("duration", com.mojang.brigadier.arguments.IntegerArgumentType.integer(10, 300))
                                .executes(context -> {
                                    testMobsStress(
                                        context.getSource().getBukkitSender(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "count"),
                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "type"),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "duration")
                                    );
                                    return 1;
                                })
                            )
                        )
                    )
                )
                .then(net.minecraft.commands.Commands.literal("chunks")
                    .then(net.minecraft.commands.Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(100, 10000))
                        .executes(context -> {
                            testChunksStress(
                                context.getSource().getBukkitSender(),
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "count")
                            );
                            return 1;
                        })
                    )
                )
                .then(net.minecraft.commands.Commands.literal("full")
                    .then(net.minecraft.commands.Commands.argument("intensity", com.mojang.brigadier.arguments.StringArgumentType.string())
                        .executes(context -> {
                            testFullStress(
                                context.getSource().getBukkitSender(),
                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "intensity")
                            );
                            return 1;
                        })
                    )
                )
                .then(net.minecraft.commands.Commands.literal("config")
                    .executes(context -> {
                        showStressConfig(context.getSource().getBukkitSender());
                        return 1;
                    })
                )
            )
        );
    }

    public static void testMobsStress(org.bukkit.command.CommandSender sender, int count, String type, int duration) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can run this test.");
            return;
        }
        
        Player player = (Player) sender;
        PerformanceMetrics metrics = new PerformanceMetrics();
        metrics.reset();
        
        sender.sendMessage("§6╔═══════════════════════════════════════════╗");
        sender.sendMessage("§6║  §e§lAdvanced Entity Stress Test§6             ║");
        sender.sendMessage("§6╠═══════════════════════════════════════════╣");
        sender.sendMessage("§6║ §7Entities: §a" + count + " §7" + type);
        sender.sendMessage("§6║ §7Duration: §a" + duration + "s");
        sender.sendMessage("§6║ §7Metrics: §bTPS, MSPT, RAM");
        sender.sendMessage("§6╚═══════════════════════════════════════════╝");
        
        // 1. Spawn Entities
        TurboTestCommand.testMobs(sender, count, type);
        
        // 2. Start Monitoring
        startStressMonitor(sender, duration, metrics);
    }
    
    // Better implementation using Thread for monitoring (since we are in core server code)
    private static void startStressMonitor(org.bukkit.command.CommandSender sender, int duration, PerformanceMetrics metrics) {
        new Thread(() -> {
            try {
                for (int i = 0; i < duration; i++) {
                    Thread.sleep(1000);
                    net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
                    double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
                    double tps = Math.min(20.0, 1000.0 / Math.max(50.0, mspt));
                    long freeMem = Runtime.getRuntime().freeMemory() / 1024 / 1024;
                    long totalMem = Runtime.getRuntime().totalMemory() / 1024 / 1024;
                    long usedMem = totalMem - freeMem;
                    
                    metrics.addSample(tps, mspt, usedMem);
                    sender.sendMessage("§7[Stress] T+" + (i+1) + "s | MSPT: §e" + String.format("%.2f", mspt) + "ms §7| RAM: §b" + usedMem + "MB");
                }
                
                finishTest(sender, metrics);
                
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void finishTest(org.bukkit.command.CommandSender sender, PerformanceMetrics metrics) {
        sender.sendMessage("§6╔═══════════════════════════════════════════╗");
        sender.sendMessage("§6║  §a§lStress Test Complete!§6                   ║");
        sender.sendMessage("§6╠═══════════════════════════════════════════╣");
        sender.sendMessage("§6║ §eResults:");
        sender.sendMessage("§6║   §7Avg TPS: §a" + String.format("%.2f", metrics.getAverageTPS()));
        sender.sendMessage("§6║   §7Avg MSPT: §e" + String.format("%.2f", metrics.getAverageMSPT()) + "ms");
        sender.sendMessage("§6║   §7Avg RAM: §b" + metrics.getAverageMemory() + "MB");
        sender.sendMessage("§6║   §7Lag Spikes: §c" + metrics.lagSpikes.get());
        sender.sendMessage("§6║ §7Run /kill @e[type=!player] to cleanup.");
        sender.sendMessage("§6╚═══════════════════════════════════════════╝");
    }

    public static void testChunksStress(org.bukkit.command.CommandSender sender, int count) {
        sender.sendMessage("§e[TurboMC] Starting Chunk Stress Test: " + count + " chunks");
        TurboTestCommand.testChunks(sender, count);
    }

    public static void testFullStress(org.bukkit.command.CommandSender sender, String intensity) {
        sender.sendMessage("§c[TurboMC] CAUTION: Starting FULL SYSTEM STRESS TEST (Intensity: " + intensity + ")");
        TurboTestCommand.testGeneration(sender, intensity.equalsIgnoreCase("high") ? 16 : 8);
    }

    private static void showStressConfig(org.bukkit.command.CommandSender sender) {
        sender.sendMessage("§6=== TurboMC Stress Configuration ===");
        sender.sendMessage("§7Current Mode: §aDynamic v2.4");
        sender.sendMessage("§7Hardware Safe Limit: §e" + com.turbomc.core.autopilot.TurboHardwareProfiler.getInstance().getOptimalThreadCount(0.5) + " threads");
    }
}
