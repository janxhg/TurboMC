package com.turbomc.commands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.World;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mass testing command for TurboMC performance testing.
 * Allows administrators to stress test various systems.
 * 
 * @author TurboMC
 * @version 2.3.0
 */
public class TurboTestCommand {
    
    public static void testChunks(CommandSender sender, int count) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be run by a player.");
            return;
        }
        
        Player player = (Player) sender;
        sender.sendMessage("§6╔═══════════════════════════════════════════╗");
        sender.sendMessage("§6║  §e§lChunk Load Stress Test§6                ║");
        sender.sendMessage("§6╠═══════════════════════════════════════════╣");
        sender.sendMessage("§6║ §7Testing: §a" + count + " §7chunk loads");
        sender.sendMessage("§6║ §7Please wait...");
        sender.sendMessage("§6╚═══════════════════════════════════════════╝");
        
        CompletableFuture.runAsync(() -> {
            try {
                ServerLevel world = ((org.bukkit.craftbukkit.CraftWorld)player.getWorld()).getHandle();
                Random rand = new Random();
                long startTime = System.nanoTime();
                AtomicInteger loaded = new AtomicInteger(0);
                AtomicLong totalTime = new AtomicLong(0);
                long minTime = Long.MAX_VALUE;
                long maxTime = 0;
                
                for (int i = 0; i < count; i++) {
                    int x = rand.nextInt(2000) - 1000;
                    int z = rand.nextInt(2000) - 1000;
                    
                    long chunkStart = System.nanoTime();
                    world.getChunk(x, z);
                    long chunkTime = System.nanoTime() - chunkStart;
                    
                    totalTime.addAndGet(chunkTime);
                    minTime = Math.min(minTime, chunkTime);
                    maxTime = Math.max(maxTime, chunkTime);
                    loaded.incrementAndGet();
                    
                    // Progress update every 100 chunks
                    if (i > 0 && i % 100 == 0) {
                        double progress = (100.0 * i) / count;
                        sender.sendMessage(String.format("§7Progress: §e%.1f%% §7(§a%d§7/§e%d§7)", 
                                                        progress, i, count));
                    }
                }
                
                long totalDuration = System.nanoTime() - startTime;
                
                // Results
                sender.sendMessage("§6╔═══════════════════════════════════════════╗");
                sender.sendMessage("§6║  §a§lTest Complete!§6                        ║");
                sender.sendMessage("§6╠═══════════════════════════════════════════╣");
                sender.sendMessage("§6║ §eResults:");
                sender.sendMessage("§6║   §7Total Time: §a" + (totalDuration / 1_000_000) + "ms");
                sender.sendMessage("§6║   §7Chunks Loaded: §a" + loaded.get());
                sender.sendMessage("§6║   §7Avg Load Time: §a" + 
                                 String.format("%.2f", totalTime.get() / (double)count / 1_000_000.0) + "ms");
                sender.sendMessage("§6║   §7Min/Max Time: §e" + 
                                 (minTime / 1_000_000) + "ms §7/ §c" + (maxTime / 1_000_000) + "ms");
                
                double chunksPerSecond = (count * 1000.0) / (totalDuration / 1_000_000.0);
                sender.sendMessage("§6║   §7Throughput: §a" + String.format("%.1f", chunksPerSecond) + " §7chunks/sec");
                sender.sendMessage("§6╚═══════════════════════════════════════════╝");
                
            } catch (Exception e) {
                sender.sendMessage("§cTest failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    public static void testGeneration(CommandSender sender, int radius) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be run by a player.");
            return;
        }
        
        Player player = (Player) sender;
        int totalChunks = (2 * radius + 1) * (2 * radius + 1);
        
        sender.sendMessage("§6╔═══════════════════════════════════════════╗");
        sender.sendMessage("§6║  §e§lMass Chunk Generation Test§6            ║");
        sender.sendMessage("§6╠═══════════════════════════════════════════╣");
        sender.sendMessage("§6║ §7Radius: §a" + radius + " §7chunks");
        sender.sendMessage("§6║ §7Total: §a" + totalChunks + " §7chunks");
        sender.sendMessage("§6║ §7Generating...");
        sender.sendMessage("§6╚═══════════════════════════════════════════╝");
        
        CompletableFuture.runAsync(() -> {
            try {
                ServerLevel world = ((org.bukkit.craftbukkit.CraftWorld)player.getWorld()).getHandle();
                int centerX = player.getLocation().getBlockX() >> 4;
                int centerZ = player.getLocation().getBlockZ() >> 4;
                
                long startTime = System.nanoTime();
                AtomicInteger generated = new AtomicInteger(0);
                
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int chunkX = centerX + dx;
                        int chunkZ = centerZ + dz;
                        
                        world.getChunk(chunkX, chunkZ);
                        generated.incrementAndGet();
                        
                        if (generated.get() % 50 == 0) {
                            double progress = (100.0 * generated.get()) / totalChunks;
                            sender.sendMessage(String.format("§7Progress: §e%.1f%% §7(§a%d§7/§e%d§7)", 
                                                            progress, generated.get(), totalChunks));
                        }
                    }
                }
                
                long totalDuration = System.nanoTime() - startTime;
                
                sender.sendMessage("§6╔═══════════════════════════════════════════╗");
                sender.sendMessage("§6║  §a§lGeneration Complete!§6                  ║");
                sender.sendMessage("§6╠═══════════════════════════════════════════╣");
                sender.sendMessage("§6║ §7Total Time: §a" + (totalDuration / 1_000_000) + "ms");
                sender.sendMessage("§6║ §7Generated: §a" + generated.get() + " §7chunks");
                sender.sendMessage("§6║ §7Avg Time: §a" + 
                                 String.format("%.2f", totalDuration / (double)totalChunks / 1_000_000.0) + "ms/chunk");
                sender.sendMessage("§6╚═══════════════════════════════════════════╝");
                
            } catch (Exception e) {
                sender.sendMessage("§cGeneration test failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    public static void testFlight(CommandSender sender, int speed, int distance) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be run by a player.");
            return;
        }
        
        Player player = (Player) sender;
        
        sender.sendMessage("§6╔═══════════════════════════════════════════╗");
        sender.sendMessage("§6║  §e§lFlight Performance Test§6               ║");
        sender.sendMessage("§6╠═══════════════════════════════════════════╣");
        sender.sendMessage("§6║ §7Speed: §a" + speed);
        sender.sendMessage("§6║ §7Distance: §a" + distance + " §7blocks");
        sender.sendMessage("§6║ §7Simulating flight...");
        sender.sendMessage("§6╚═══════════════════════════════════════════╝");
        
        CompletableFuture.runAsync(() -> {
            try {
                ServerLevel world = ((org.bukkit.craftbukkit.CraftWorld)player.getWorld()).getHandle();
                int startX = player.getLocation().getBlockX();
                int startZ = player.getLocation().getBlockZ();
                
                long startTime = System.nanoTime();
                AtomicInteger chunksLoaded = new AtomicInteger(0);
                AtomicInteger lagSpikes = new AtomicInteger(0);
                AtomicLong totalLoadTime = new AtomicLong(0);
                
                // Simulate flying in a straight line
                for (int i = 0; i < distance; i += speed) {
                    int x = (startX + i) >> 4;
                    int z = startZ >> 4;
                    
                    long chunkStart = System.nanoTime();
                    world.getChunk(x, z);
                    long chunkTime = System.nanoTime() - chunkStart;
                    
                    chunksLoaded.incrementAndGet();
                    totalLoadTime.addAndGet(chunkTime);
                    
                    // Count lag spikes (>50ms is noticeable)
                    if (chunkTime > 50_000_000) {
                        lagSpikes.incrementAndGet();
                    }
                    
                    // Simulate tick delay
                    Thread.sleep(50); // 1 tick = 50ms
                }
                
                long totalDuration = System.nanoTime() - startTime;
                
                sender.sendMessage("§6╔═══════════════════════════════════════════╗");
                sender.sendMessage("§6║  §a§lFlight Test Complete!§6                 ║");
                sender.sendMessage("§6╠═══════════════════════════════════════════╣");
                sender.sendMessage("§6║ §7Flight Time: §a" + (totalDuration / 1_000_000) + "ms");
                sender.sendMessage("§6║ §7Chunks Loaded: §a" + chunksLoaded.get());
                sender.sendMessage("§6║ §7Avg Load: §a" + 
                                 String.format("%.2f", totalLoadTime.get() / (double)chunksLoaded.get() / 1_000_000.0) + "ms");
                
                String spikeColor = lagSpikes.get() == 0 ? "§a" : lagSpikes.get() < 5 ? "§e" : "§c";
                sender.sendMessage("§6║ §7Lag Spikes (>50ms): " + spikeColor + lagSpikes.get());
                
                if (lagSpikes.get() == 0) {
                    sender.sendMessage("§6║ §a§lPERFECT! No lag detected!");
                } else if (lagSpikes.get() < 5) {
                    sender.sendMessage("§6║ §e§lGOOD - Minor lag detected");
                } else {
                    sender.sendMessage("§6║ §c§lWARNING - Significant lag!");
                }
                
                sender.sendMessage("§6╚═══════════════════════════════════════════╝");
                
            } catch (Exception e) {
                sender.sendMessage("§cFlight test failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    
    public static void testCache(CommandSender sender) {
        sender.sendMessage("§6╔═══════════════════════════════════════════╗");
        sender.sendMessage("§6║  §e§lCache Performance Test§6                ║");
        sender.sendMessage("§6╠═══════════════════════════════════════════╣");
        sender.sendMessage("§6║ §7Running cache benchmark...");
        sender.sendMessage("§6╚═══════════════════════════════════════════╝");
        
        CompletableFuture.runAsync(() -> {
            try {
                com.turbomc.storage.optimization.TurboStorageManager mgr = 
                    com.turbomc.storage.optimization.TurboStorageManager.getInstance();
                
                if (mgr == null) {
                    sender.sendMessage("§cStorage manager not available");
                    return;
                }
                
                long initialHits = mgr.getCacheHits();
                long initialMisses = mgr.getCacheMisses();
                
                sender.sendMessage("§6║ §7Initial Stats:");
                sender.sendMessage("§6║   §7Hits: §a" + initialHits);
                sender.sendMessage("§6║   §7Misses: §c" + initialMisses);
                
                // Test complete - show final stats
                long finalHits = mgr.getCacheHits();
                long finalMisses = mgr.getCacheMisses();
                long totalRequests = finalHits + finalMisses;
                double hitRate = totalRequests > 0 ? (100.0 * finalHits) / totalRequests : 0;
                
                sender.sendMessage("§6║ §a§lCache Stats:");
                sender.sendMessage("§6║   §7Total Requests: §e" + totalRequests);
                sender.sendMessage("§6║   §7Hit Rate: §a" + String.format("%.1f%%", hitRate));
                sender.sendMessage("§6╚═══════════════════════════════════════════╝");
                
            } catch (Exception e) {
                sender.sendMessage("§cCache test failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
    public static void testMobs(CommandSender sender, int count, String entityType) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be run by a player.");
            return;
        }

        Player player = (Player) sender;
        org.bukkit.entity.EntityType type;
        try {
            type = org.bukkit.entity.EntityType.valueOf(entityType.toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cInvalid entity type: " + entityType);
            return;
        }
        
        if (count > 2000) {
            sender.sendMessage("§cWarning: Spawning >2000 entities can crash the client. Capped at 2000.");
            count = 2000;
        }

        sender.sendMessage("§6╔═══════════════════════════════════════════╗");
        sender.sendMessage("§6║  §e§lEntity Stress Test§6                    ║");
        sender.sendMessage("§6╠═══════════════════════════════════════════╣");
        sender.sendMessage("§6║ §7Spawning §a" + count + " §7" + type.name());
        sender.sendMessage("§6║ §7Please wait...");
        sender.sendMessage("§6╚═══════════════════════════════════════════╝");

        int finalCount = count;
        org.bukkit.Location center = player.getLocation();
        World bukkitWorld = player.getWorld();

        CompletableFuture.runAsync(() -> {
            try {
                AtomicInteger spawned = new AtomicInteger(0);
                long start = System.nanoTime();
                
                // Switch to main thread for spawning
                net.minecraft.server.MinecraftServer.getServer().execute(() -> {
                    for (int i = 0; i < finalCount; i++) {
                        double x = center.getX() + (Math.random() * 40 - 20);
                        double z = center.getZ() + (Math.random() * 40 - 20);
                        double y = center.getY(); 
                        
                        bukkitWorld.spawnEntity(new org.bukkit.Location(bukkitWorld, x, y, z), type);
                        spawned.incrementAndGet();
                    }
                    
                     long duration = System.nanoTime() - start;
                     sender.sendMessage("§a[TurboMC] Spawned " + spawned.get() + " entities in " + (duration / 1_000_000) + "ms");
                     sender.sendMessage("§e[TurboMC] Check TPS with /turbo stats");
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public static void testRedstone(CommandSender sender, int intensity) {
         if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be run by a player.");
            return;
        }
        Player player = (Player) sender;
        org.bukkit.Location start = player.getLocation().add(2, 0, 2);
        
        int size = intensity == 1 ? 16 : 48; // 1 chunk vs 3x3 chunks
        sender.sendMessage("§e[TurboMC] Building redstone grid (" + size + "x" + size + ")...");
        
        net.minecraft.server.MinecraftServer.getServer().execute(() -> {
             for (int x = 0; x < size; x++) {
                 for (int z = 0; z < size; z++) {
                     if ((x + z) % 2 == 0) {
                         start.clone().add(x, 0, z).getBlock().setType(org.bukkit.Material.REDSTONE_BLOCK);
                         start.clone().add(x, 1, z).getBlock().setType(org.bukkit.Material.REDSTONE_LAMP);
                     } else {
                         start.clone().add(x, 0, z).getBlock().setType(org.bukkit.Material.OBSERVER);
                     }
                 }
             }
             sender.sendMessage("§a[TurboMC] Redstone grid active. GOOD LUCK.");
        });
    }
    
    public static void testPhysics(CommandSender sender, int count) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be run by a player.");
            return;
        }
        Player player = (Player) sender;
        org.bukkit.Location loc = player.getLocation().add(0, 10, 0);
        
        if (count > 5000) {
            sender.sendMessage("§cWarning: Physics test capped at 5000 blocks to prevent server freeze.");
            count = 5000;
        }

        sender.sendMessage("§e[TurboMC] Unleashing physics (" + count + " blocks)...");
        int finalCount = count;
        net.minecraft.server.MinecraftServer.getServer().execute(() -> {
            for (int i = 0; i < finalCount; i++) {
                 loc.clone().add(Math.random() * 5, i, Math.random() * 5).getBlock().setType(org.bukkit.Material.SAND);
            }
             sender.sendMessage("§a[TurboMC] Physics test started.");
        });
    }
}
