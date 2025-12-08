package com.turbomc.via;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viabackwards.api.ViaBackwardsPlatform;

import java.io.File;

public class TurboViaLoader implements ViaPlatformLoader {
    private static boolean initialized = false;

    public static void init(File dataFolder) {
        if (initialized) return;
        
        try {
            // 1. Initialize Platform
            TurboViaPlatform platform = new TurboViaPlatform(dataFolder);
            Via.init(platform, new TurboViaInjector());

            // 2. Initialize Loader
            TurboViaLoader loader = new TurboViaLoader();
            loader.load();
            
            // 3. Initialize Sub-platforms (Backwards) if present
            new ViaBackwardsPlatformImpl(dataFolder).init(dataFolder);

            initialized = true;
            platform.getLogger().info("TurboVia (ViaVersion Native) has been initialized!");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void load() {
        // Register protocols? 
        // Usually ViaVersion handles this automatically if correct dependencies are loaded.
    }

    public static void inject(io.netty.channel.Channel channel) {
        if (!initialized) return;
        try {
            com.viaversion.viaversion.connection.UserConnectionImpl user = new com.viaversion.viaversion.connection.UserConnectionImpl(channel, true);
            new com.viaversion.viaversion.protocol.ProtocolPipelineImpl(user);
            
            // Add to pipeline
            // We need to add before the Minecraft packet handler (usually "packet_handler" or "decoder")
            // In patches "packet_handler" is the name used for the connection
            // But we are in initChannel, so "packet_handler" might not be added yet or is being added.
            // In ServerConnectionListener, connection.configurePacketHandler adds "packet_handler"
            // So we are inserting BEFORE that.
            
            // ViaVersion needs to be before the decoder.
            // 'timeout' is usually first.
            // 'haproxy-decoder' might be there.
            // We can just add to the start/end depending on flow.
            // For server: 
            // Decoder: Bytes -> Packets (Read)
            // Encoder: Packets -> Bytes (Write)
            // ViaDecoder: Bytes (Client) -> Bytes (Server version)
            // ViaEncoder: Bytes (Server version) -> Bytes (Client version)
            
            // So ViaDecoder must be BEFORE Minecraft Decoder.
            // ViaEncoder must be AFTER Minecraft Encoder.
            
            // Actually, ViaVersion usually inserts "via-decoder" and "via-encoder".
            // Since we are calling this BEFORE configurePacketHandler, "packet_handler" isn't there.
            // We can add to First?
            
            channel.pipeline().addLast("via-decoder", new com.viaversion.viaversion.bukkit.handlers.BukkitDecodeHandler(user));
            channel.pipeline().addLast("via-encoder", new com.viaversion.viaversion.bukkit.handlers.BukkitEncodeHandler(user));
            
            // We need to ensure these stay before the MC handlers. 
            // Since we add them now, and MC adds "packet_handler" later (appended), 
            // "via-decoder" will be BEFORE "packet_handler" in the Inbound chain?
            // Netty AddLast: [A, B, C]
            // Inbound: A -> B -> C
            // So if we add Via, then MC adds PacketHandler.
            // Via -> PacketHandler. Correct.
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // Simple logic for backwards compatibility wrappers
    static class ViaBackwardsPlatformImpl implements com.viaversion.viabackwards.api.ViaBackwardsPlatform {
        private final File folder;
        public ViaBackwardsPlatformImpl(File folder) { this.folder = folder; }
        @Override
        public void disable() {}
        @Override
        public File getDataFolder() { return new File(folder, "ViaBackwards"); }
        @Override
        public java.util.logging.Logger getLogger() { return java.util.logging.Logger.getLogger("ViaBackwards"); }
        @Override
        public boolean isOutdated() { return false; }
        @Override
        public void init(File dataFolder) {
             com.viaversion.viabackwards.api.ViaBackwardsPlatform.super.init(dataFolder);
        }
    }
    
}
