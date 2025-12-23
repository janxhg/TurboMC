package com.turbomc.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

/**
 * Placeholder for TurboInspectorCommand.
 * The actual inspector functionality is implemented in TurboCommandRegistry.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public final class TurboInspectorCommand {
    
    private TurboInspectorCommand() {
        // Utility class
    }
    
    /**
     * Register inspector commands with the command dispatcher.
     * This is a placeholder - actual commands are registered in TurboCommandRegistry.
     * 
     * @param dispatcher The command dispatcher to register commands with
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Inspector commands are registered in TurboCommandRegistry
        // This is just a placeholder to satisfy the registration call
        System.out.println("[TurboMC][Inspector] Inspector commands registered via TurboCommandRegistry");
    }
}
