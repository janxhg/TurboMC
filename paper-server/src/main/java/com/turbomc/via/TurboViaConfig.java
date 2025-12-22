package com.turbomc.via;

import com.viaversion.viaversion.api.configuration.ViaVersionConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import com.viaversion.viaversion.libs.fastutil.ints.IntOpenHashSet;
import com.viaversion.viaversion.libs.fastutil.ints.IntSet;
import java.util.Map;
import com.turbomc.config.TurboConfig;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class TurboViaConfig implements ViaVersionConfig {
    private final File configFile;
    private YamlConfiguration config;
    private final TurboConfig turboConfig;

    public TurboViaConfig(File configFile) {
        this.configFile = configFile;
        this.turboConfig = TurboConfig.getInstance(configFile.getParentFile());
        reload();
    }

    public void reload() {
        this.config = YamlConfiguration.loadConfiguration(configFile);
        config.addDefault("checkforupdates", true);
        config.addDefault("hologram-patch", false);
        config.addDefault("hologram-y-offset", -0.96);
        config.addDefault("max-pps", -1);
        config.addDefault("max-pps-kick-msg", "Sending packets too fast");
        config.addDefault("tracking-period", 6);
        config.addDefault("tracking-warning-pps", 120);
        config.addDefault("tracking-max-warnings", 3);
        config.addDefault("tracking-max-kick-msg", "You are sending too many packets!");
        config.options().copyDefaults(true);
        save();
    }

    @Override
    public Map<String, Object> getValues() {
        return config.getValues(false);
    }

    @Override
    public void set(String path, Object value) {
        config.set(path, value);
    }

    public void save() {
        try {
            config.save(configFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean isCheckForUpdates() {
        return config.getBoolean("checkforupdates", true);
    }

    @Override
    public void setCheckForUpdates(boolean check) {
        config.set("checkforupdates", check);
        save();
    }

    // Keep without @Override - may not be in interface anymore
    public boolean isPreventCollision() { return false; }
    public boolean isNewEffectIndicator() { return false; }
    public boolean isShowNewDeathMessages() { return false; }
    public boolean isSuppressMetadataErrors() { return false; }
    public boolean isShieldBlocking() { return false; }
    public boolean isNoDelayShieldBlocking() { return false; }
    public boolean isShowShieldWhenSwordInHand() { return false; }

    @Override
    public boolean isHologramPatch() {
        return config.getBoolean("hologram-patch", false);
    }

    @Override
    public boolean isPistonAnimationPatch() { return false; }
    
    @Override
    public boolean isBossbarPatch() { return true; }
    
    @Override
    public boolean isBossbarAntiflicker() { return false; }

    @Override
    public double getHologramYOffset() {
        return config.getDouble("hologram-y-offset", -0.96);
    }

    @Override
    public int getMaxPPS() { return config.getInt("max-pps", -1); }
    @Override
    public String getMaxPPSKickMessage() { return config.getString("max-pps-kick-msg", "Sending packets too fast"); }
    @Override
    public int getTrackingPeriod() { return config.getInt("tracking-period", 6); }
    @Override
    public int getWarningPPS() { return config.getInt("tracking-warning-pps", 120); }
    @Override
    public int getMaxWarnings() { return config.getInt("tracking-max-warnings", 3); }
    @Override
    public String getMaxWarningsKickMessage() { return config.getString("tracking-max-kick-msg", "You are sending too many packets!"); }

    // Logic removed from interface? Keeping implementations without @Override
    public boolean isSendSupportedVersions() { return false; }
    public boolean isSimulatePlayerTick() { return true; }
    public boolean isItemCache() { return false; }
    public boolean isNMSPlayerTicking() { return true; }
    public boolean isReplacePistons() { return false; }
    public int getPistonReplacementId() { return -1; }
    
    public boolean isChunkBorderFix() { return false; }
    
    public boolean isEjectingBlockedListPacket() { return true; }
    public boolean is1_12QuickMoveActionFix() { return false; }

    @Override
    public boolean cache1_17Light() { return false; }
    
    public IntSet getBlockedProtocols() { return new IntOpenHashSet(); }
    @Override
    public String getBlockedDisconnectMsg() { return "Unsupported protocol version"; }
    @Override
    public String getReloadDisconnectMsg() { return "Server reloading"; }

    public boolean isMinimizeCooldown() { return true; }
    public boolean is1_13TabCompleteDelay() { return true; }
    public List<String> getUncache1_13TabComplete() { return Arrays.asList(); }

    @Override
    public int get1_13TabCompleteDelay() { return 0; }
    
    // ViaVersion 4.9.0 API - Métodos faltantes
    @Override
    public com.viaversion.viaversion.api.minecraft.WorldIdentifiers get1_16WorldNamesMap() {
        return null; // Stub implementation
    }

    @Override
    public com.viaversion.viaversion.libs.gson.JsonElement get1_17ResourcePackPrompt() {
        return null; // Stub implementation
    }

    public boolean isInfestedBlocksFix() { return false; }

    public boolean isSnowCollisionFix() { return false; }

    public boolean isVineClimbFix() { return false; }

    public boolean isStemWhenBlockAbove() { return false; }

    public boolean isReduceBlockStorageMemory() { return false; }

    public String getBlockConnectionMethod() { return "off"; }

    public boolean isServersideBlockConnections() { return false; }

    public boolean isDisable1_13AutoComplete() { return false; }

    public boolean isSuppressConversionWarnings() { return false; }

    @Override
    public com.viaversion.viaversion.api.protocol.version.BlockedProtocolVersions blockedProtocolVersions() {
        try {
            // Leer configuración desde turbo.toml [version-control]
            List<String> blockedVersions = turboConfig.getBlockedVersions();
            String minVersion = turboConfig.getMinimumVersion();
            String maxVersion = turboConfig.getMaximumVersion();
            
            // Convertir versiones de texto a números de protocolo
            IntSet blockedProtocols = new IntOpenHashSet();
            
            // Agregar versiones bloqueadas explícitamente
            for (String version : blockedVersions) {
                int protocol = versionToProtocol(version);
                if (protocol > 0) {
                    blockedProtocols.add(protocol);
                }
            }
            
            // Bloquear versiones fuera del rango min-max
            int minProtocol = versionToProtocol(minVersion);
            int maxProtocol = versionToProtocol(maxVersion);
            
            if (minProtocol > 0 && maxProtocol > 0) {
                // Bloquear todas las versiones menores a la mínima
                for (int i = 0; i < minProtocol; i++) {
                    blockedProtocols.add(i);
                }
                // Bloquear todas las versiones mayores a la máxima
                for (int i = maxProtocol + 1; i <= 1000; i++) {
                    blockedProtocols.add(i);
                }
            }
            
            // Crear implementación válida de BlockedProtocolVersions
            return new com.viaversion.viaversion.api.protocol.version.BlockedProtocolVersions() {
                public IntSet getProtocols() {
                    return blockedProtocols;
                }
                
                public String getKickMessage() {
                    return "Version no soportada. Usa una versión entre " + minVersion + " y " + maxVersion;
                }
                
                public IntSet singleBlockedVersions() {
                    return blockedProtocols;
                }
                
                public int blocksAbove() {
                    return maxProtocol > 0 ? maxProtocol : -1;
                }
                
                public int blocksBelow() {
                    return minProtocol > 0 ? minProtocol : -1;
                }
                
                public boolean contains(int protocol) {
                    return blockedProtocols.contains(protocol);
                }
            };
        } catch (Exception e) {
            e.printStackTrace();
            // Fallback a valores por defecto
            return new com.viaversion.viaversion.api.protocol.version.BlockedProtocolVersions() {
                public IntSet getProtocols() {
                    return new IntOpenHashSet();
                }
                
                public String getKickMessage() {
                    return "Version no soportada";
                }
                
                public IntSet singleBlockedVersions() {
                    return new IntOpenHashSet();
                }
                
                public int blocksAbove() {
                    return -1;
                }
                
                public int blocksBelow() {
                    return -1;
                }
                
                public boolean contains(int protocol) {
                    return false;
                }
            };
        }
    }
    
    /**
     * Convierte una versión de Minecraft (ej: "1.20.1") a número de protocolo
     */
    private int versionToProtocol(String version) {
        if (version == null || version.isEmpty()) return -1;
        
        // Mapeo de versiones a números de protocolo (actualizado)
        switch (version) {
            case "1.20.1": return 763;
            case "1.20.2": return 764;
            case "1.20.3": case "1.20.4": return 765;
            case "1.20.5": case "1.20.6": return 766;
            case "1.21": return 767;
            case "1.21.1": return 768;
            case "1.21.2": return 769;
            case "1.21.3": return 770;
            case "1.21.4": return 771;
            case "1.21.5": return 772;
            case "1.21.6": return 773;
            case "1.21.7": return 774;
            case "1.21.8": return 775;
            case "1.21.9": return 776;
            case "1.21.10": return 777;
            default: return -1; // Versión no reconocida
        }
    }

    public boolean shouldRegisterUserConnectionOnJoin() { return false; }

    public boolean is1_13TeamColourFix() { return false; }

    public boolean is1_12NBTArrayFix() { return false; }

    public boolean isAutoTeam() { return false; }

    public boolean isForceJsonTransform() { return false; }

    public boolean isTruncate1_14Books() { return false; }
    public boolean isLeftHandedHandling() { return true; }
    public boolean is1_9HitboxFix() { return false; }
    public boolean is1_14HitboxFix() { return false; }
    public boolean isNonFullBlockLightFix() { return false; }
    public boolean is1_14HealthNaNFix() { return false; }
    public boolean is1_15InstantRespawn() { return false; }
    public boolean isIgnoreLong1_16ChannelNames() { return true; }
    public boolean isForcedUse1_17ResourcePack() { return false; }
    public boolean isResourcePack1_17PromptMessage() { return false; }
    public boolean is1_17GPFPacketFix() { return true; }
    public boolean isFix1_17ChatColor() { return true; }
    public boolean isUse1_19_4ArgumentTypeFix() { return true; }
    public boolean isArmorToggleFix() { return true; }
    
    public boolean fix1_21PlacementRotation() {
        return false;
    }
    
    public boolean hideScoreboardNumbers() {
        return false;
    }
    
    public boolean cancelBlockSounds() {
        return false;
    }
}
