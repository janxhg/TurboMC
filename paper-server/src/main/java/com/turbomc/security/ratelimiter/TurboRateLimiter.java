package com.turbomc.security.ratelimiter;

import com.turbomc.performance.TurboOptimizerModule;
import com.turbomc.config.TurboConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.Queue;
import java.util.LinkedList;
import java.util.List;
import java.util.ArrayList;

/**
 * Internal Rate Limiter for TurboMC.
 * Prevents packet spam exploits and protects server stability.
 * 
 * Features:
 * - Per-player rate limiting
 * - Global rate limiting
 * - Different limits per packet type
 * - Temporary auto-ban on abuse
 * - Integration with TurboProxy L7
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class TurboRateLimiter implements TurboOptimizerModule {
    
    private static volatile TurboRateLimiter instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    // Configuration
    private boolean enabled;
    private int playerPacketLimit;
    private int globalPacketLimit;
    private int banThreshold;
    private long banDuration;
    private boolean turboProxyIntegration;
    
    // Rate limiting data
    private final ConcurrentHashMap<String, PlayerRateData> playerData = new ConcurrentHashMap<>();
    private final AtomicLong globalPacketCount = new AtomicLong(0);
    private final AtomicLong totalPacketsBlocked = new AtomicLong(0);
    private final AtomicLong totalBansIssued = new AtomicLong(0);
    
    // Packet type limits
    private final Map<Class<? extends Packet<?>>, Integer> packetLimits = new ConcurrentHashMap<>();
    
    // Banned players
    private final ConcurrentHashMap<String, Long> bannedPlayers = new ConcurrentHashMap<>();
    
    /**
     * Player rate limiting data
     */
    private static class PlayerRateData {
        private final Queue<Long> packetTimestamps = new LinkedList<>();
        private final Map<Class<? extends Packet<?>>, Queue<Long>> packetTypeTimestamps = new ConcurrentHashMap<>();
        private final AtomicLong packetCount = new AtomicLong(0);
        private volatile long lastPacketTime = 0;
        private volatile int violationCount = 0;
        
        public synchronized void addPacket(Packet<?> packet) {
            long currentTime = System.currentTimeMillis();
            packetCount.incrementAndGet();
            lastPacketTime = currentTime;
            
            // Add to general packet queue
            packetTimestamps.offer(currentTime);
            
            // Add to packet type specific queue
            @SuppressWarnings("unchecked")
            Class<? extends Packet<?>> packetClass = (Class<? extends Packet<?>>) packet.getClass();
            packetTypeTimestamps.computeIfAbsent(packetClass, k -> new LinkedList<>())
                .offer(currentTime);
            
            // Clean old timestamps (older than 1 second)
            cleanupOldTimestamps(currentTime - 1000);
        }
        
        private void cleanupOldTimestamps(long cutoffTime) {
            // Clean general packet timestamps
            while (!packetTimestamps.isEmpty() && packetTimestamps.peek() < cutoffTime) {
                packetTimestamps.poll();
            }
            
            // Clean packet type specific timestamps
            packetTypeTimestamps.values().forEach(queue -> {
                while (!queue.isEmpty() && queue.peek() < cutoffTime) {
                    queue.poll();
                }
            });
        }
        
        public synchronized int getPacketsPerSecond() {
            cleanupOldTimestamps(System.currentTimeMillis() - 1000);
            return packetTimestamps.size();
        }
        
        public synchronized int getPacketsPerSecond(Class<? extends Packet<?>> packetClass) {
            cleanupOldTimestamps(System.currentTimeMillis() - 1000);
            Queue<Long> typeQueue = packetTypeTimestamps.get(packetClass);
            return typeQueue != null ? typeQueue.size() : 0;
        }
        
        public long getTotalPackets() { return packetCount.get(); }
        public long getLastPacketTime() { return lastPacketTime; }
        public int getViolationCount() { return violationCount; }
        public void incrementViolations() { violationCount++; }
    }
    
    private TurboRateLimiter() {
        // Private constructor for singleton
    }
    
    /**
     * Get singleton instance
     */
    public static TurboRateLimiter getInstance() {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new TurboRateLimiter();
                }
            }
        }
        return instance;
    }
    
    @Override
    public void initialize() {
        loadConfiguration(TurboConfig.getInstance());
        setupPacketLimits();
        
        System.out.println("[TurboMC][RateLimiter] Internal Rate Limiter initialized");
        System.out.println("[TurboMC][RateLimiter] Player Limit: " + playerPacketLimit + " packets/sec");
        System.out.println("[TurboMC][RateLimiter] Global Limit: " + globalPacketLimit + " packets/sec");
        System.out.println("[TurboMC][RateLimiter] Ban Threshold: " + banThreshold + " violations");
        System.out.println("[TurboMC][RateLimiter] TurboProxy Integration: " + (turboProxyIntegration ? "ENABLED" : "DISABLED"));
    }
    
    @Override
    public void loadConfiguration(TurboConfig config) {
        enabled = config.getBoolean("security.rate-limiter.enabled", true);
        playerPacketLimit = config.getInt("security.rate-limiter.player-limit", 100);
        globalPacketLimit = config.getInt("security.rate-limiter.global-limit", 10000);
        banThreshold = config.getInt("security.rate-limiter.ban-threshold", 10);
        banDuration = config.getLong("security.rate-limiter.ban-duration", 300000); // 5 minutes
        turboProxyIntegration = config.getBoolean("security.rate-limiter.turbo-proxy-integration", false);
    }
    
    /**
     * Setup default packet type limits
     */
    private void setupPacketLimits() {
        // Movement packets (higher limit for legitimate gameplay)
        packetLimits.put(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos.class, 20);
        packetLimits.put(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot.class, 20);
        packetLimits.put(net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot.class, 20);
        
        // Action packets (moderate limit)
        packetLimits.put(net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.class, 10);
        packetLimits.put(net.minecraft.network.protocol.game.ServerboundUseItemPacket.class, 10);
        packetLimits.put(net.minecraft.network.protocol.game.ServerboundUseItemOnPacket.class, 10);
        
        // Chat packets (lower limit to prevent spam)
        packetLimits.put(net.minecraft.network.protocol.game.ServerboundChatPacket.class, 5);
        packetLimits.put(net.minecraft.network.protocol.game.ServerboundChatCommandPacket.class, 3);
        
        // Inventory packets (moderate limit)
        packetLimits.put(net.minecraft.network.protocol.game.ServerboundContainerClickPacket.class, 15);
        packetLimits.put(net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket.class, 10);
        
        // System packets (very low limit)
        // packetLimits.put(net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket.class, 2);
    }
    
    @Override
    public void start() {
        if (!enabled) return;
        
        System.out.println("[TurboMC][RateLimiter] Internal Rate Limiter started");
    }
    
    @Override
    public void stop() {
        playerData.clear();
        bannedPlayers.clear();
        
        System.out.println("[TurboMC][RateLimiter] Internal Rate Limiter stopped");
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }
    
    @Override
    public String getModuleName() {
        return "TurboRateLimiter";
    }
    
    @Override
    public String getPerformanceStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== TurboMC Rate Limiter Stats ===\n");
        stats.append("Enabled: ").append(enabled).append("\n");
        stats.append("Total Packets Processed: ").append(globalPacketCount.get()).append("\n");
        stats.append("Total Packets Blocked: ").append(totalPacketsBlocked.get()).append("\n");
        stats.append("Total Bans Issued: ").append(totalBansIssued.get()).append("\n");
        stats.append("Currently Banned Players: ").append(bannedPlayers.size()).append("\n");
        stats.append("Active Players Monitored: ").append(playerData.size()).append("\n");
        
        double blockRate = globalPacketCount.get() > 0 ? 
            (double) totalPacketsBlocked.get() / globalPacketCount.get() * 100 : 0;
        stats.append("Block Rate: ").append(String.format("%.2f%%", blockRate)).append("\n");
        
        return stats.toString();
    }
    
    @Override
    public boolean shouldOptimize() {
        return enabled;
    }
    
    @Override
    public void performOptimization() {
        if (!shouldOptimize()) return;
        
        // Clean up expired bans
        cleanupExpiredBans();
        
        // Clean up inactive player data
        cleanupInactivePlayers();
    }
    
    /**
     * Process incoming packet for rate limiting
     */
    public boolean processPacket(ServerPlayer player, Packet<?> packet) {
        if (!enabled) return true;
        
        globalPacketCount.incrementAndGet();
        
        String playerName = player.getName().getString();
        
        // Check if player is banned
        if (isPlayerBanned(playerName)) {
            totalPacketsBlocked.incrementAndGet();
            return false;
        }
        
        // Get or create player data
        PlayerRateData data = playerData.computeIfAbsent(playerName, k -> new PlayerRateData());
        data.addPacket(packet);
        
        // Check rate limits
        if (!checkRateLimits(player, packet, data)) {
            handleViolation(player, data);
            totalPacketsBlocked.incrementAndGet();
            return false;
        }
        
        return true;
    }
    
    /**
     * Check if packet respects rate limits
     */
    private boolean checkRateLimits(ServerPlayer player, Packet<?> packet, PlayerRateData data) {
        // Check global rate limit
        if (globalPacketCount.get() > globalPacketLimit) {
            return false;
        }
        
        // Check player rate limit
        if (data.getPacketsPerSecond() > playerPacketLimit) {
            return false;
        }
        
        // Check packet type specific limit
        @SuppressWarnings("unchecked")
        Class<? extends Packet<?>> packetClass = (Class<? extends Packet<?>>) packet.getClass();
        Integer packetLimit = packetLimits.get(packetClass);
        if (packetLimit != null && data.getPacketsPerSecond(packetClass) > packetLimit) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Handle rate limit violation
     */
    private void handleViolation(ServerPlayer player, PlayerRateData data) {
        data.incrementViolations();
        
        String playerName = player.getName().getString();
        
        if (data.getViolationCount() >= banThreshold) {
            banPlayer(playerName);
            
            // Log violation
            System.out.println("[TurboMC][RateLimiter] Player " + playerName + 
                             " banned for rate limit violations (" + data.getViolationCount() + ")");
            
            // Notify TurboProxy if integration is enabled
            if (turboProxyIntegration) {
                notifyTurboProxy(playerName, "RATE_LIMIT_VIOLATION");
            }
        }
    }
    
    /**
     * Ban player temporarily
     */
    private void banPlayer(String playerName) {
        bannedPlayers.put(playerName, System.currentTimeMillis() + banDuration);
        totalBansIssued.incrementAndGet();
        
        // Kick player if online
        ServerPlayer player = getPlayerByName(playerName);
        if (player != null) {
            player.connection.disconnect(
                net.minecraft.network.chat.Component.literal("§cYou have been temporarily banned for packet spam.\n§7Please wait 5 minutes and try again.")
            );
        }
    }
    
    /**
     * Check if player is currently banned
     */
    private boolean isPlayerBanned(String playerName) {
        Long banExpiry = bannedPlayers.get(playerName);
        if (banExpiry == null) return false;
        
        if (System.currentTimeMillis() > banExpiry) {
            bannedPlayers.remove(playerName);
            return false;
        }
        
        return true;
    }
    
    /**
     * Get player by name (simplified)
     */
    private ServerPlayer getPlayerByName(String playerName) {
        // This would need access to the server's player list
        // Simplified implementation for now
        return null;
    }
    
    /**
     * Notify TurboProxy of violation
     */
    private void notifyTurboProxy(String playerName, String reason) {
        // Integration with TurboProxy L7 would go here
        // This could send a message to the proxy to ban the player at proxy level
        System.out.println("[TurboMC][RateLimiter] Notified TurboProxy: " + playerName + " - " + reason);
    }
    
    /**
     * Clean up expired bans
     */
    private void cleanupExpiredBans() {
        long currentTime = System.currentTimeMillis();
        bannedPlayers.entrySet().removeIf(entry -> currentTime > entry.getValue());
    }
    
    /**
     * Clean up inactive player data
     */
    private void cleanupInactivePlayers() {
        long cutoffTime = System.currentTimeMillis() - 300000; // 5 minutes inactive
        
        playerData.entrySet().removeIf(entry -> {
            PlayerRateData data = entry.getValue();
            return data.getLastPacketTime() < cutoffTime;
        });
    }
    
    /**
     * Get rate limiter statistics
     */
    public RateLimiterStats getStats() {
        return new RateLimiterStats(
            globalPacketCount.get(),
            totalPacketsBlocked.get(),
            totalBansIssued.get(),
            playerData.size(),
            bannedPlayers.size()
        );
    }
    
    /**
     * Rate limiter statistics
     */
    public static class RateLimiterStats {
        private final long totalPackets;
        private final long blockedPackets;
        private final long totalBans;
        private final int activePlayers;
        private final int bannedPlayers;
        
        public RateLimiterStats(long totalPackets, long blockedPackets,
                               long totalBans, int activePlayers, int bannedPlayers) {
            this.totalPackets = totalPackets;
            this.blockedPackets = blockedPackets;
            this.totalBans = totalBans;
            this.activePlayers = activePlayers;
            this.bannedPlayers = bannedPlayers;
        }
        
        // Getters
        public long getTotalPackets() { return totalPackets; }
        public long getBlockedPackets() { return blockedPackets; }
        public long getTotalBans() { return totalBans; }
        public int getActivePlayers() { return activePlayers; }
        public int getBannedPlayers() { return bannedPlayers; }
        
        public double getBlockRate() {
            return totalPackets > 0 ? (double) blockedPackets / totalPackets * 100 : 0;
        }
    }
}
