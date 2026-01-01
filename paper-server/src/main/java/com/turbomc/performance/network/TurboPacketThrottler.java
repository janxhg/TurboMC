package com.turbomc.performance.network;

import com.turbomc.core.autopilot.HealthMonitor;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turbo Packet Throttler - v2.4.0
 * Manages incoming packet load to prevent main-thread stalls.
 */
public class TurboPacketThrottler {

    private static final TurboPacketThrottler INSTANCE = new TurboPacketThrottler();
    public static TurboPacketThrottler getInstance() { return INSTANCE; }

    private final ConcurrentHashMap<java.util.UUID, PlayerPacketMetrics> playerMetrics = new ConcurrentHashMap<>();
    private final AtomicLong totalThrottled = new AtomicLong(0);

    private TurboPacketThrottler() {}

    public boolean shouldThrottle(ServerPlayer player, Packet<?> packet) {
        HealthMonitor health = HealthMonitor.getInstance();
        
        // Critical packets are never throttled
        if (isCritical(packet)) {
            return false;
        }

        PlayerPacketMetrics metrics = playerMetrics.computeIfAbsent(player.getUUID(), k -> new PlayerPacketMetrics());
        long now = System.currentTimeMillis();
        
        // Per-second reset
        if (now - metrics.lastReset.get() > 1000) {
            metrics.packetCount.set(0);
            metrics.lastReset.set(now);
        }

        int count = metrics.packetCount.incrementAndGet();
        
        // Dynamic threshold based on health
        int threshold = 200; // Default: 200 packets per second per player
        if (health.isOverloaded()) {
            threshold = 50; 
        } else if (health.isUnderPressure()) {
            threshold = 100;
        }

        if (count > threshold) {
            if (isHighFrequency(packet)) {
                totalThrottled.incrementAndGet();
                return true;
            }
        }

        return false;
    }

    private boolean isCritical(Packet<?> packet) {
        // Add more critical packets as needed
        return packet instanceof net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
    }

    private boolean isHighFrequency(Packet<?> packet) {
        return packet instanceof ServerboundMovePlayerPacket ||
               packet instanceof ServerboundSwingPacket ||
               packet instanceof ServerboundUseItemOnPacket;
    }

    public void cleanup(ServerPlayer player) {
        playerMetrics.remove(player.getUUID());
    }

    private static class PlayerPacketMetrics {
        final AtomicInteger packetCount = new AtomicInteger(0);
        final AtomicLong lastReset = new AtomicLong(System.currentTimeMillis());
    }
}
