package com.turbomc.compression;

/**
 * Exception thrown when compression or decompression operations fail.
 */
public class CompressionException extends Exception {
    public CompressionException(String message) {
        super(message);
    }
    
    public CompressionException(String message, Throwable cause) {
        super(message, cause);
    }
}
