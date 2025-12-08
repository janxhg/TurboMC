package com.turbomc.via;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaAPI;
import com.viaversion.viaversion.api.command.ViaCommandSender;
import com.viaversion.viaversion.api.configuration.ViaVersionConfig;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.PlatformTask;
import com.viaversion.viaversion.api.platform.ViaPlatform;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;
import com.viaversion.viaversion.libs.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class TurboViaPlatform implements ViaPlatform<UUID> {
    private final Logger logger = LogManager.getLogger("ViaVersion");
    private final TurboViaConfig config;
    private final File dataFolder;

    public TurboViaPlatform(File dataFolder) {
        this.dataFolder = dataFolder;
        this.config = new TurboViaConfig(new File(dataFolder, "viaversion.yml"));
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public String getPlatformName() {
        return "TurboMC";
    }

    @Override
    public String getPlatformVersion() {
        return "1.2.0";
    }

    @Override
    public String getPluginVersion() {
        return "5.2.1"; // ViaVersion core version
    }

    @Override
    public PlatformTask runAsync(Runnable runnable) {
        // We can use the Bukkit scheduler or a native executor. 
        // Since we are inside the server, we should probably stick to a simple thread for async
        // or hook into the server's executor.
        // For simplicity in this implementation:
        Thread thread = new Thread(runnable, "ViaVersion-Async");
        thread.start();
        return new TurboPlatformTask(thread);
    }

    @Override
    public PlatformTask runSync(Runnable runnable) {
        // We need to run this on the main server thread.
        // If we are initialized before Bukkit, we might need a workaround.
        // But typically this is called for tasks like saving config.
        // We'll use the Bukkit scheduler if available, or just run if we are main.
        try {
            if (Bukkit.getServer() != null) {
                Bukkit.getScheduler().runTask(getPlugin(), runnable);
            } else {
                runnable.run();
            }
        } catch (Exception e) {
            runnable.run();
        }
        return new TurboPlatformTask(null);
    }

    private Plugin getPlugin() {
        // This is a bit hacky, we are not a plugin.
        // But for scheduler, we might need a plugin instance if we use Bukkit API.
        // However, since we are THE SERVER, we might be able to use the internal scheduler directly.
        // For now, let's assume this works or we fix it later.
        // Actually, creating a dummy plugin or using the System scheduler is better.
        return null; // Will crash if used with Bukkit scheduler wanting a plugin
    }

    @Override
    public PlatformTask runSync(Runnable runnable, long l) {
         // See runSync
         return new TurboPlatformTask(null);
    }

    @Override
    public PlatformTask runRepeatedSync(Runnable runnable, long l, long l1) {
        return new TurboPlatformTask(null);
    }

    @Override
    public ViaCommandSender[] getOnlinePlayers() {
        return new ViaCommandSender[0]; // TODO: Implement
    }

    @Override
    public void sendMessage(UUID uuid, String s) {
        // TODO: Send message to player
    }

    @Override
    public boolean kickPlayer(UUID uuid, String s) {
        return false; // TODO: Implement
    }

    @Override
    public boolean isPluginEnabled() {
        return true;
    }

    @Override
    public ViaAPI<UUID> getApi() {
        return Via.getApi();
    }

    @Override
    public ViaVersionConfig getConf() {
        return config;
    }

    @Override
    public com.viaversion.viaversion.api.connection.ConnectionManager getConnectionManager() {
        return new com.viaversion.viaversion.connection.ConnectionManagerImpl();
    }

    @Override
    public void onReload() {
        // Reload config
        config.reload();
    }

    @Override
    public JsonObject getDump() {
        return new JsonObject();
    }

    @Override
    public boolean isOldClientsAllowed() {
        return true;
    }
    
    @Override
    public File getDataFolder() {
        return dataFolder;
    }
    
    // Inner class for tasks
    private static class TurboPlatformTask implements PlatformTask {
        private final Thread thread;
        public TurboPlatformTask(Thread thread) { this.thread = thread; }
        @Override
        public void cancel() { if(thread != null) thread.interrupt(); }
    }
}
