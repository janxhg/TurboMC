package com.turbomc.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

/**
 * Registry for all TurboMC commands.
 * This class registers all TurboMC administrative commands with Paper's command system.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public final class TurboCommandRegistry {
    
    private TurboCommandRegistry() {
        // Utility class
    }
    
    /**
     * Register all TurboMC commands.
     * Call this during server startup after commands are initialized.
     * 
     * @param dispatcher The command dispatcher to register commands with
     */
    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Register storage management commands
        TurboStorageCommand.register(dispatcher);
        
        // Future commands can be registered here
        // TurboPerformanceCommand.register(dispatcher);
        // TurboConfigCommand.register(dispatcher);
        
        System.out.println("[TurboMC][Commands] Registered " + getCommandCount() + " commands");
    }
    
    /**
     * Get the number of registered commands.
     */
    public static int getCommandCount() {
        // Currently only storage commands are registered
        return 1;
    }
    
    /**
     * Get a list of all registered command names.
     */
    public static String[] getRegisteredCommands() {
        return new String[]{
            "turbo storage"
        };
    }
}
