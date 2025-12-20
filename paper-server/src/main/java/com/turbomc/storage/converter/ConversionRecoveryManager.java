package com.turbomc.storage.converter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages conversion recovery and error handling for TurboMC LRF conversions.
 * Provides rollback capabilities and detailed error logging.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class ConversionRecoveryManager {
    
    private final AtomicLong recoveryCount = new AtomicLong(0);
    private final AtomicLong rollbackCount = new AtomicLong(0);
    private final boolean enableRecovery;
    private final boolean enableBackups;
    
    public ConversionRecoveryManager(boolean enableRecovery, boolean enableBackups) {
        this.enableRecovery = enableRecovery;
        this.enableBackups = enableBackups;
    }
    
    /**
     * Create backup of original file before conversion.
     * 
     * @param originalPath Path to original file
     * @return Path to backup file or null if backup failed
     */
    public Path createBackup(Path originalPath) {
        if (!enableBackups) {
            return null;
        }
        
        try {
            Path backupDir = originalPath.getParent().resolve("conversion_backup_" + 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));
            Files.createDirectories(backupDir);
            
            String fileName = originalPath.getFileName().toString();
            Path backupPath = backupDir.resolve(fileName);
            
            Files.copy(originalPath, backupPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            System.out.println("[TurboMC] Created backup: " + fileName + " → " + backupDir.getFileName());
            return backupPath;
            
        } catch (IOException e) {
            System.err.println("[TurboMC] Failed to create backup: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Attempt to recover from conversion failure.
     * 
     * @param failedPath Path to failed conversion result
     * @param originalPath Path to original file
     * @return True if recovery successful
     */
    public boolean recoverFromFailure(Path failedPath, Path originalPath) {
        if (!enableRecovery) {
            return false;
        }
        
        try {
            if (Files.exists(failedPath)) {
                Files.delete(failedPath);
            }
            
            recoveryCount.incrementAndGet();
            System.out.println("[TurboMC] Recovery successful: removed failed file " + failedPath.getFileName());
            return true;
            
        } catch (IOException e) {
            System.err.println("[TurboMC] Recovery failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Perform rollback to original format.
     * 
     * @param lrfPath Path to LRF file
     * @param targetDir Directory for rollback result
     * @return True if rollback successful
     */
    public boolean rollbackToMCA(Path lrfPath, Path targetDir) {
        if (!enableRecovery) {
            return false;
        }
        
        try {
            LRFToMCAConverter converter = new LRFToMCAConverter(true);
            
            String fileName = lrfPath.getFileName().toString();
            String mcaFileName = fileName.replace(".lrf", ".mca");
            Path mcaPath = targetDir.resolve(mcaFileName);
            
            converter.convert(lrfPath, mcaPath);
            rollbackCount.incrementAndGet();
            
            System.out.println("[TurboMC] Rollback successful: " + fileName + " → " + mcaFileName);
            return true;
            
        } catch (Exception e) {
            System.err.println("[TurboMC] Rollback failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Validate file integrity after conversion.
     * 
     * @param filePath Path to file to validate
     * @param expectedFormat Expected format (MCA or LRF)
     * @return True if file passes validation
     */
    public boolean validateConversion(Path filePath, RegionConverter.FormatType expectedFormat) {
        try {
            if (!Files.exists(filePath)) {
                System.err.println("[TurboMC] Validation failed: file does not exist " + filePath.getFileName());
                return false;
            }
            
            if (Files.size(filePath) == 0) {
                System.err.println("[TurboMC] Validation failed: file is empty " + filePath.getFileName());
                return false;
            }
            
            RegionConverter.FormatType detectedFormat = RegionConverter.detectFormat(filePath);
            if (detectedFormat != expectedFormat) {
                System.err.println("[TurboMC] Validation failed: expected " + expectedFormat + " but got " + detectedFormat + 
                                 " for " + filePath.getFileName());
                return false;
            }
            
            return true;
            
        } catch (IOException e) {
            System.err.println("[TurboMC] Validation failed: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get recovery statistics.
     */
    public RecoveryStats getStats() {
        return new RecoveryStats(
            recoveryCount.get(),
            rollbackCount.get()
        );
    }
    
    /**
     * Recovery statistics holder.
     */
    public static class RecoveryStats {
        public final long recoveryCount;
        public final long rollbackCount;
        
        public RecoveryStats(long recoveryCount, long rollbackCount) {
            this.recoveryCount = recoveryCount;
            this.rollbackCount = rollbackCount;
        }
        
        @Override
        public String toString() {
            return String.format("RecoveryStats{recoveries=%d, rollbacks=%d}",
                    recoveryCount, rollbackCount);
        }
    }
}
