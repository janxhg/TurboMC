package com.turbomc.via;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaAPI;
import com.viaversion.viaversion.api.command.ViaCommandSender;
import com.viaversion.viaversion.api.configuration.ViaVersionConfig;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.PlatformTask;
import com.viaversion.viaversion.api.platform.ViaPlatform;
import com.viaversion.viaversion.libs.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.UUID;
import java.util.logging.Level;

public class TurboViaPlatform implements ViaPlatform<UUID> {
    private final Logger logger = LogManager.getLogger("TurboVia");
    private final File dataFolder;
    private final TurboViaConfig config;

    public TurboViaPlatform(File dataFolder) {
        this.dataFolder = new File(dataFolder, "ViaVersion");
        if (!this.dataFolder.exists()) this.dataFolder.mkdirs();
        this.config = new TurboViaConfig(new File(this.dataFolder, "config.yml"));
    }

    @Override
    public java.util.logging.Logger getLogger() {
        return new java.util.logging.Logger("TurboVia", null) {
            @Override
            public void log(Level level, String msg) {
                logger.info("[ViaVersion] " + msg);
            }
        };
    }

    @Override
    public String getPlatformName() { return "TurboMC"; }
    @Override
    public String getPlatformVersion() { return "1.6.0"; }
    @Override
    public String getPluginVersion() { return "4.9.0"; }

    @Override
    public PlatformTask runAsync(Runnable runnable) {
        // MCUtil.scheduleAsyncTask returns void, so just run and return dummy task
        io.papermc.paper.util.MCUtil.scheduleAsyncTask(runnable);
        return new TurboPlatformTask(null);
    }

    @Override
    public PlatformTask runSync(Runnable runnable) {
        runnable.run();
        return new TurboPlatformTask(null);
    }

    @Override
    public PlatformTask runSync(Runnable runnable, long l) {
        return new TurboPlatformTask(null);
    }

    @Override
    public PlatformTask runRepeatingSync(Runnable runnable, long period) {
        return new TurboPlatformTask(null);
    }

    @Override
    public PlatformTask runRepeatingAsync(Runnable runnable, long period) {
        return new TurboPlatformTask(null);
    }

    @Override
    public ViaCommandSender[] getOnlinePlayers() { return new ViaCommandSender[0]; }
    @Override
    public void sendMessage(UUID uuid, String s) {}
    @Override
    public boolean kickPlayer(UUID uuid, String s) { return false; }
    @Override
    public boolean isPluginEnabled() { return true; }

    @Override
    public ViaAPI<UUID> getApi() {
        return Via.getAPI();
    }

    @Override
    public ViaVersionConfig getConf() { return config; }
    @Override
    public void onReload() { 
        config.reload(); 
    }
    @Override
    public com.viaversion.viaversion.libs.gson.JsonObject getDump() { return new com.viaversion.viaversion.libs.gson.JsonObject(); }
    
    @Override
    public boolean isOldClientsAllowed() { return true; }
    
    @Override
    public boolean hasPlugin(String name) { return false; }
    
    @Override
    public File getDataFolder() { return dataFolder; }
    
    public com.viaversion.viaversion.api.connection.ConnectionManager getConnectionManager() {
        return Via.getManager().getConnectionManager();
    }

    private static class TurboPlatformTask implements PlatformTask {
        private final Object handle;
        public TurboPlatformTask(Object handle) { this.handle = handle; }
        @Override
        public Object getObject() { return handle; }
        public void cancel() {}
    }
}
