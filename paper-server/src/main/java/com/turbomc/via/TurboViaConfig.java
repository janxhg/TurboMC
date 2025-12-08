package com.turbomc.via;

import com.viaversion.viaversion.api.ViaVersionConfig;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TurboViaConfig implements ViaVersionConfig {
    private final File configFile;
    private YamlConfiguration config;

    public TurboViaConfig(File configFile) {
        this.configFile = configFile;
        reload();
    }

    public void reload() {
        this.config = YamlConfiguration.loadConfiguration(configFile);
        // Set defaults
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

    @Override
    public boolean isPreventCollision() {
        return false;
    }

    @Override
    public boolean isNewEffectIndicator() {
        return false;
    }

    @Override
    public boolean isShowNewDeathMessages() {
        return false;
    }

    @Override
    public boolean isSuppressMetadataErrors() {
        return false;
    }

    @Override
    public boolean isShieldBlocking() {
        return false;
    }

    @Override
    public boolean isNoDelayShieldBlocking() {
        return false;
    }

    @Override
    public boolean isShowShieldWhenSwordInHand() {
        return false;
    }

    @Override
    public boolean isHologramPatch() {
        return config.getBoolean("hologram-patch", false);
    }

    @Override
    public boolean isPistonAnimationPatch() {
        return false;
    }

    @Override
    public boolean isBossbarPatch() {
        return true;
    }

    @Override
    public boolean isBossbarAntiflicker() {
        return false;
    }

    @Override
    public double getHologramYOffset() {
        return config.getDouble("hologram-y-offset", -0.96);
    }

    @Override
    public int getMaxPPS() {
        return config.getInt("max-pps", -1);
    }

    @Override
    public String getMaxPPSKickMessage() {
        return config.getString("max-pps-kick-msg", "Sending packets too fast");
    }

    @Override
    public int getTrackingPeriod() {
        return config.getInt("tracking-period", 6);
    }

    @Override
    public int getWarningPPS() {
        return config.getInt("tracking-warning-pps", 120);
    }

    @Override
    public int getMaxWarnings() {
        return config.getInt("tracking-max-warnings", 3);
    }

    @Override
    public String getMaxWarningsKickMessage() {
        return config.getString("tracking-max-kick-msg", "You are sending too many packets!");
    }

    @Override
    public boolean isSendSupportedVersions() {
        return false;
    }

    @Override
    public boolean isSimulatePlayerTick() {
        return true;
    }

    @Override
    public boolean isItemCache() {
        return false;
    }

    @Override
    public boolean isNMSPlayerTicking() {
        return true;
    }

    @Override
    public boolean isReplacePistons() {
        return false;
    }

    @Override
    public int getPistonReplacementId() {
        return -1;
    }

    @Override
    public boolean isChunkBorderFix() {
        return false;
    }

    @Override
    public boolean isEjectingBlockedListPacket() {
        return true;
    }

    @Override
    public boolean is1_12QuickMoveActionFix() {
        return false;
    }

    @Override
    public List<String> getBlockedProtocols() {
        return Arrays.asList();
    }

    @Override
    public String getBlockedDisconnectMsg() {
        return "Unsupported protocol version";
    }

    @Override
    public String getReloadDisconnectMsg() {
        return "Server reloading";
    }

    @Override
    public boolean isMinimizeCooldown() {
        return true;
    }

    @Override
    public boolean is1_13TabCompleteDelay() {
        return true;
    }

    @Override
    public List<String> getUncache1_13TabComplete() {
        return Arrays.asList();
    }

    @Override
    public Map<String, Integer> get1_13TabCompleteDelay() {
        return Map.of();
    }

    @Override
    public boolean isTruncate1_14Books() {
        return false;
    }

    @Override
    public boolean isLeftHandedHandling() {
        return true;
    }

    @Override
    public boolean is1_9HitboxFix() {
        return false;
    }

    @Override
    public boolean is1_14HitboxFix() {
        return false;
    }

    @Override
    public boolean isNonFullBlockLightFix() {
        return false;
    }

    @Override
    public boolean is1_14HealthNaNFix() {
        return false;
    }

    @Override
    public boolean is1_15InstantRespawn() {
        return false;
    }

    @Override
    public boolean isIgnoreLong1_16ChannelNames() {
        return true;
    }

    @Override
    public boolean isForcedUse1_17ResourcePack() {
        return false;
    }

    @Override
    public boolean isResourcePack1_17PromptMessage() {
        return false;
    }

    @Override
    public boolean is1_17GPFPacketFix() {
        return true;
    }

    @Override
    public boolean isFix1_17ChatColor() {
        return true;
    }

    @Override
    public boolean isUse1_19_4ArgumentTypeFix() {
        return true;
    }
    
    @Override
    public boolean isArmorToggleFix() {
        return true;
    }
}
