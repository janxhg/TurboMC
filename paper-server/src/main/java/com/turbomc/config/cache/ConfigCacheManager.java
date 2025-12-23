package com.turbomc.config.cache;

import com.moandjiezana.toml.Toml;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages the binary caching of YAML/JSON configurations.
 * <p>
 * This system creates a .bin version of text config files.
 * Format:
 * [Header] "T_CFG"
 * [Hash] 32 bytes (SHA-256 of source text)
 * [Data] Serialized HashMap
 */
@SuppressWarnings("unchecked")
public class ConfigCacheManager {

    private static final byte[] MAGIC_HEADER = "T_CFG".getBytes(StandardCharsets.US_ASCII);

    /**
     * Loads a configuration map, using the binary cache if available and valid.
     * Uses SnakeYAML to parse the source if cache is invalid or missing.
     */
    public static Map<String, Object> loadWithCache(Path sourceFile) {
        Path cacheFile = sourceFile.resolveSibling(sourceFile.getFileName() + ".bin");

        try {
            // 1. Check if source exists
            if (!Files.exists(sourceFile)) {
                return null;
            }

            byte[] sourceBytes = Files.readAllBytes(sourceFile);
            byte[] currentHash = calculateHash(sourceBytes);

            // 2. Try to load from cache
            if (Files.exists(cacheFile)) {
                Map<String, Object> cachedData = loadFromCache(cacheFile, currentHash);
                if (cachedData != null) {
                    System.out.println("[TurboMC][Cache] Loaded config from binary cache: " + cacheFile.getFileName());
                    return cachedData;
                }
            }

            // 3. Fallback to parsing YAML and update cache
            System.out.println("[TurboMC][Cache] Miss/Invalid. Parsing YAML: " + sourceFile.getFileName());
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(new String(sourceBytes, StandardCharsets.UTF_8));
            
            if (data == null) data = new HashMap<>(); // Empty file case

            saveToCache(cacheFile, currentHash, data);
            return data;

        } catch (Exception e) {
            System.err.println("[TurboMC][Cache] Error managing config cache: " + e.getMessage());
            e.printStackTrace();
            // Final fallback: try to load standard without cache logic involved
            try {
                return new Yaml().load(Files.readString(sourceFile));
            } catch (IOException ex) {
                return null;
            }
        }
    }

    private static Map<String, Object> loadFromCache(Path cacheFile, byte[] expectedHash) {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(cacheFile)))) {
            // Read Header
            byte[] magic = new byte[MAGIC_HEADER.length];
            dis.readFully(magic);
            if (!MessageDigest.isEqual(magic, MAGIC_HEADER)) return null;

            // Read Hash
            byte[] storedHash = new byte[32]; // SHA-256 is 32 bytes
            dis.readFully(storedHash);

            if (!MessageDigest.isEqual(storedHash, expectedHash)) {
                return null; // Cache invalid (source changed)
            }

            // Read Data (Object serialization for now - simpler for arbitrary Maps)
            // Ideally we'd use a more specialized format, but Java Serialization is safest for unknown Map<String, Object> depth
            // unless we write a custom serializer for standard YAML types (Map, List, Primitives).
            // Let's us custom serialization to avoid security issues with ObjectInputStream.
            
            return readMap(dis);

        } catch (Exception e) {
            // Corrupted cache or version mismatch
            return null; 
        }
    }

    private static void saveToCache(Path cacheFile, byte[] hash, Map<String, Object> data) {
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(cacheFile)))) {
            dos.write(MAGIC_HEADER);
            dos.write(hash);
            writeMap(dos, data);
        } catch (IOException e) {
            System.err.println("[TurboMC][Cache] Failed to write cache: " + e.getMessage());
        }
    }

    private static byte[] calculateHash(byte[] content) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(content);
    }

    // === Simple Binary Serialization for Config Types ===

    private static void writeObject(DataOutputStream dos, Object obj) throws IOException {
        if (obj instanceof Map) {
            dos.writeByte(1);
            writeMap(dos, (Map<String, Object>) obj);
        } else if (obj instanceof Iterable) { // List/Set
            dos.writeByte(2);
            writeList(dos, (Iterable<?>) obj);
        } else if (obj instanceof String) {
            dos.writeByte(3);
            dos.writeUTF((String) obj);
        } else if (obj instanceof Integer) {
            dos.writeByte(4);
            dos.writeInt((Integer) obj);
        } else if (obj instanceof Double) {
            dos.writeByte(5);
            dos.writeDouble((Double) obj);
        } else if (obj instanceof Boolean) {
            dos.writeByte(6);
            dos.writeBoolean((Boolean) obj);
        } else if (obj instanceof Long) { // YAML can parse large ints as Long
            dos.writeByte(7);
            dos.writeLong((Long) obj);
        } else if (obj == null) {
            dos.writeByte(0);
        } else {
            // Fallback for unknown types (toString)
            dos.writeByte(3);
            dos.writeUTF(obj.toString());
        }
    }

    private static Object readObject(DataInputStream dis) throws IOException {
        byte type = dis.readByte();
        switch (type) {
            case 0: return null; // Null
            case 1: return readMap(dis);
            case 2: return readList(dis);
            case 3: return dis.readUTF();
            case 4: return dis.readInt();
            case 5: return dis.readDouble();
            case 6: return dis.readBoolean();
            case 7: return dis.readLong();
            default: throw new IOException("Unknown type ID in config cache: " + type);
        }
    }

    private static void writeMap(DataOutputStream dos, Map<String, Object> map) throws IOException {
        dos.writeInt(map.size());
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            dos.writeUTF(entry.getKey());
            writeObject(dos, entry.getValue());
        }
    }

    private static Map<String, Object> readMap(DataInputStream dis) throws IOException {
        int size = dis.readInt();
        Map<String, Object> map = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            String key = dis.readUTF();
            Object value = readObject(dis);
            map.put(key, value);
        }
        return map;
    }

    private static void writeList(DataOutputStream dos, Iterable<?> list) throws IOException {
        // We need size. For iterable, we might need to copy to collection first or count.
        // Assuming Lists mostly.
        java.util.Collection<?> col;
        if (list instanceof java.util.Collection) {
            col = (java.util.Collection<?>) list;
        } else {
            // copy
             java.util.List<Object> temp = new java.util.ArrayList<>();
             list.forEach(temp::add);
             col = temp;
        }
        
        dos.writeInt(col.size());
        for (Object o : col) {
            writeObject(dos, o);
        }
    }

    private static java.util.List<Object> readList(DataInputStream dis) throws IOException {
        int size = dis.readInt();
        java.util.List<Object> list = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(readObject(dis));
        }
        return list;
    }
}
