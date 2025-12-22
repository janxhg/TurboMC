package com.turbomc.storage.optimization;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletableFuture;

/**
 * Centralized exception handler for TurboMC storage operations.
 * Provides structured logging and proper error recovery strategies.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public final class TurboExceptionHandler {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Logger STORAGE_LOGGER = LoggerFactory.getLogger("TurboMC.Storage");
    
    private TurboExceptionHandler() {
        // Utility class
    }
    
    /**
     * Handle I/O exceptions with structured logging and recovery strategy.
     */
    public static <T> T handleIOException(String operation, String context, IOExceptionSupplier<T> operationSupplier) throws IOException {
        try {
            return operationSupplier.get();
        } catch (IOException e) {
            logStructuredError("IO_ERROR", operation, context, e, 
                "path=" + extractPath(e),
                "message=" + e.getMessage());
            
            // Attempt recovery based on error type
            if (isRecoverableIOError(e)) {
                STORAGE_LOGGER.warn("[TurboMC][Recovery] Attempting recovery for {} in {}", operation, context);
                return attemptRecovery(operation, context, operationSupplier);
            }
            
            throw e;
        }
    }
    
    /**
     * Handle timeout exceptions with retry logic.
     */
    public static <T> T handleTimeout(String operation, String context, TimeoutSupplier<T> operationSupplier, int maxRetries) throws Exception {
        Exception lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return operationSupplier.get(attempt);
            } catch (TimeoutException e) {
                lastException = e;
                logStructuredError("TIMEOUT", operation, context, e,
                    "attempt=" + attempt,
                    "maxRetries=" + maxRetries,
                    "timeoutMs=" + getTimeoutMs(e));
                
                if (attempt < maxRetries) {
                    long backoffMs = calculateBackoff(attempt);
                    STORAGE_LOGGER.warn("[TurboMC][Retry] Retrying {} in {}ms (attempt {}/{})", 
                        operation, backoffMs, attempt, maxRetries);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedException("Operation interrupted during retry: " + operation);
                    }
                }
            } catch (Exception e) {
                lastException = e;
                if (!isRetryableError(e)) {
                    logStructuredError("NON_RETRYABLE", operation, context, e,
                        "attempt=" + attempt,
                        "errorType=" + e.getClass().getSimpleName());
                    throw e;
                }
                
                logStructuredError("RETRYABLE_ERROR", operation, context, e,
                    "attempt=" + attempt,
                    "maxRetries=" + maxRetries);
                
                if (attempt < maxRetries) {
                    long backoffMs = calculateBackoff(attempt);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedException("Operation interrupted during retry: " + operation);
                    }
                }
            }
        }
        
        throw new Exception("Operation failed after " + maxRetries + " attempts: " + operation, lastException);
    }
    
    /**
     * Handle general exceptions with proper logging.
     */
    public static void handleException(String operation, String context, Exception e) {
        logStructuredError("GENERAL_ERROR", operation, context, e,
            "errorType=" + e.getClass().getSimpleName(),
            "message=" + e.getMessage());
    }
    
    /**
     * Handle exceptions in CompletableFuture chains.
     */
    public static <T> CompletableFuture<T> handleAsyncException(String operation, String context, CompletableFuture<T> future) {
        return future.exceptionally(throwable -> {
            Exception e = throwable instanceof Exception ? (Exception) throwable : new Exception(throwable);
            handleException(operation, context, e);
            return null;
        });
    }
    
    /**
     * Log structured error information.
     */
    private static void logStructuredError(String errorType, String operation, String context, 
                                         Throwable e, String... details) {
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("[TurboMC][Error] type=").append(errorType);
        logMessage.append(" operation=").append(operation);
        logMessage.append(" context=").append(context);
        
        for (String detail : details) {
            logMessage.append(" ").append(detail);
        }
        
        if (STORAGE_LOGGER.isErrorEnabled()) {
            STORAGE_LOGGER.error(logMessage.toString(), e);
        } else {
            LOGGER.error(logMessage.toString(), e);
        }
    }
    
    /**
     * Check if an I/O error is recoverable.
     */
    private static boolean isRecoverableIOError(IOException e) {
        String message = e.getMessage();
        if (message == null) return false;
        
        // Common recoverable errors
        return message.contains("No space left on device") ||
               message.contains("Device or resource busy") ||
               message.contains("Too many open files") ||
               message.contains("Connection reset") ||
               message.contains("Network is unreachable");
    }
    
    /**
     * Attempt recovery from I/O error.
     */
    private static <T> T attemptRecovery(String operation, String context, IOExceptionSupplier<T> operationSupplier) throws IOException {
        // Simple retry for recovery
        try {
            Thread.sleep(100); // Brief pause
            return operationSupplier.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Recovery interrupted: " + operation, ie);
        } catch (Exception e) {
            throw new IOException("Recovery failed for: " + operation, e);
        }
    }
    
    /**
     * Check if an error is retryable.
     */
    private static boolean isRetryableError(Exception e) {
        return e instanceof IOException ||
               e instanceof TimeoutException ||
               (e.getMessage() != null && e.getMessage().contains("temporarily unavailable"));
    }
    
    /**
     * Calculate exponential backoff delay.
     */
    private static long calculateBackoff(int attempt) {
        // Exponential backoff: 100ms, 200ms, 400ms, 800ms, max 2000ms
        long delay = Math.min(100L * (1L << (attempt - 1)), 2000L);
        return delay;
    }
    
    /**
     * Extract path information from exception.
     */
    private static String extractPath(IOException e) {
        String message = e.getMessage();
        if (message != null && message.contains("(")) {
            int start = message.lastIndexOf("(");
            int end = message.lastIndexOf(")");
            if (start != -1 && end != -1 && end > start) {
                return message.substring(start + 1, end);
            }
        }
        return "unknown";
    }
    
    /**
     * Extract timeout information.
     */
    private static long getTimeoutMs(TimeoutException e) {
        String message = e.getMessage();
        if (message != null && message.contains("ms")) {
            try {
                int start = message.indexOf("ms") - 10;
                if (start < 0) start = 0;
                String substring = message.substring(start, message.indexOf("ms"));
                return Long.parseLong(substring.replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {
                // Fall back to default
            }
        }
        return -1; // Unknown timeout
    }
    
    @FunctionalInterface
    public interface IOExceptionSupplier<T> {
        T get() throws IOException;
    }
    
    @FunctionalInterface
    public interface TimeoutSupplier<T> {
        T get(int attempt) throws Exception;
    }
}
