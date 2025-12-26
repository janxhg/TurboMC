package com.turbomc.commands;

import com.turbomc.world.TurboWorldManager;
import com.turbomc.world.ChunkPrefetcher;
import net.minecraft.commands.CommandSourceStack;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class TurboHyperViewCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("turbo")
            .then(Commands.literal("hyperview")
                .requires(source -> source.hasPermission(2, "turbomc.admin.hyperview"))
                .then(Commands.argument("radius", IntegerArgumentType.integer(8, 128))
                    .executes(context -> setRadius(
                        context.getSource(),
                        IntegerArgumentType.getInteger(context, "radius"),
                        null
                    ))
                    .then(Commands.argument("world", StringArgumentType.word())
                        .executes(context -> setRadius(
                            context.getSource(),
                            IntegerArgumentType.getInteger(context, "radius"),
                            StringArgumentType.getString(context, "world")
                        ))
                    )
                )
            )
        );
    }
    
    private static int setRadius(CommandSourceStack source, int radius, String worldName) {
        String effectiveWorldName;
        if (worldName == null) {
            // Use current world if player, or "world" if console
            try {
                if (source.getLevel() != null) {
                    effectiveWorldName = source.getLevel().dimension().location().toString();
                } else {
                    effectiveWorldName = "minecraft:overworld";
                }
            } catch (Exception e) {
                effectiveWorldName = "minecraft:overworld";
            }
        } else {
            effectiveWorldName = worldName;
        }
        
        ChunkPrefetcher prefetcher = TurboWorldManager.getInstance().getPrefetcher(effectiveWorldName);
        // Use central autopilot to set and apply the radius
        com.turbomc.core.autopilot.TurboAutopilot.getInstance().setRequestedRadius(radius);
        
        int finalRadius = com.turbomc.core.autopilot.TurboAutopilot.getInstance().getEffectiveHyperViewRadius();
        String finalName = effectiveWorldName;
        
        if (finalRadius < radius) {
            source.sendSuccess(() -> Component.literal("§a[TurboMC] HyperView radius set to §e" + finalRadius + " §a(clamped from " + radius + " by Autopilot) for " + finalName), true);
        } else {
            source.sendSuccess(() -> Component.literal("§a[TurboMC] HyperView radius set to " + radius + " chunks for " + finalName), true);
        }
        return 1;
    }

    // Bukkit support
    public static void execute(org.bukkit.command.CommandSender sender, int radius, String worldName) {
         try {
             String effectiveWorldName = worldName;
             if (effectiveWorldName == null) {
                 if (sender instanceof org.bukkit.entity.Player) {
                     effectiveWorldName = ((org.bukkit.entity.Player) sender).getWorld().getName();
                     // Map Bukkit name to vanilla dimension if needed, or rely on TurboWorldManager handling it
                     // TurboWorldManager uses "minecraft:overworld" etc. usually from ServerLevel.dimension().location().
                     // Bukkit world.getName() usually returns "world", "world_nether", "world_the_end".
                     // We need to match what TurboWorldManager expects.
                     // The Brigadier command uses dimension().location().toString() ("minecraft:overworld").
                     // Let's iterate keys or try to match.
                     
                     // Helper:
                     org.bukkit.World w = ((org.bukkit.entity.Player) sender).getWorld();
                     net.minecraft.server.level.ServerLevel sl = ((org.bukkit.craftbukkit.CraftWorld) w).getHandle();
                     effectiveWorldName = sl.dimension().location().toString();
                 } else {
                     effectiveWorldName = "minecraft:overworld";
                 }
             }
             
             ChunkPrefetcher prefetcher = TurboWorldManager.getInstance().getPrefetcher(effectiveWorldName);
             if (prefetcher == null) {
                 // Try fallback keys just in case
                  if (effectiveWorldName.contains("overworld")) prefetcher = TurboWorldManager.getInstance().getPrefetcher("minecraft:overworld");
             }
             
             if (prefetcher == null) {
                 sender.sendMessage("§cHyperView is not active for world: " + effectiveWorldName);
                 // List available?
                 return;
             }
             
             prefetcher.setRadius(radius);
             sender.sendMessage("§a[TurboMC] HyperView radius set to " + radius + " chunks for " + effectiveWorldName);
             
         } catch (Exception e) {
             sender.sendMessage("§cError setting HyperView: " + e.getMessage());
         }
    }
}
