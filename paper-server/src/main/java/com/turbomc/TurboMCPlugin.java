package com.turbomc;

import com.turbomc.compression.TurboCompressionService;
import com.turbomc.commands.TurboCommandRegistry;
import com.turbomc.config.TurboConfig;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;

import java.util.logging.Logger;

public class TurboMCPlugin extends JavaPlugin {
    private static final Logger LOGGER = Logger.getLogger("TurboMC");
    
    private TurboCompressionService compressionService;
    private TurboCommandRegistry commandRegistry;
    private TurboConfig turboConfig;
    
    @Override
    public void onEnable() {
        LOGGER.info("TurboMC Plugin starting up...");
        
        try {
            // Initialize configuration
            saveDefaultConfig();
            this.turboConfig = TurboConfig.getInstance(getDataFolder().getParentFile());
            
            // Initialize compression service
            initializeCompressionService();
            
            // Initialize modules
            initializeCommands();
            
            LOGGER.info("TurboMC Plugin enabled successfully!");
            
        } catch (Exception e) {
            LOGGER.severe("Failed to enable TurboMC Plugin: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    
    @Override
    public void onDisable() {
        LOGGER.info("TurboMC Plugin shutting down...");
        
        try {
            // Shutdown services in reverse order
            if (compressionService != null) {
                // compressionService.cleanup(); // TODO: Implement when available
            }
            
            LOGGER.info("TurboMC Plugin disabled successfully!");
            
        } catch (Exception e) {
            LOGGER.severe("Error during TurboMC Plugin shutdown: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void initializeCompressionService() {
        try {
            this.compressionService = TurboCompressionService.getInstance();
            LOGGER.info("Turbo Compression Service linked successfully");
        } catch (Exception e) {
            LOGGER.severe("Failed to link Compression Service: " + e.getMessage());
            throw e;
        }
    }
    
    private void initializeCommands() {
        try {
            this.commandRegistry = TurboCommandRegistry.create();
            LOGGER.info("Turbo Commands registered");
        } catch (Exception e) {
            LOGGER.severe("Failed to initialize Commands: " + e.getMessage());
            throw e;
        }
    }
    
    // Public getters for other components
    public TurboCompressionService getCompressionService() {
        return compressionService; 
    }
    
    public TurboCommandRegistry getCommandRegistry() {
        return commandRegistry;
    }
    
    public TurboConfig getTurboConfig() {
        return turboConfig;
    }
    
    // Link to the internal storage manager
    public com.turbomc.storage.optimization.TurboStorageManager getStorageManager() {
        return com.turbomc.storage.optimization.TurboStorageManager.getInstance();
    }
    
    // Configuration methods
    @Override
    public void reloadConfig() {
        super.reloadConfig();
        if (turboConfig != null) {
            turboConfig.reload();
        }
    }
    
    // Utility methods
    public static Logger getTurboLogger() {
        return LOGGER;
    }
    
    public boolean isTurboModeEnabled() {
        return getConfig().getBoolean("turbo.enabled", true);
    }
}
