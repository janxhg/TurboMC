package com.turbomc.nbt;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.turbomc.config.TurboConfig;
import com.turbomc.compression.TurboCompressionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

import java.util.UUID;

public class PackedBinaryNBTTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    public void setup() {
        TurboConfig.resetInstance();
        TurboCompressionService.resetInstance();
        
        // Initialize config (creates defaults)
        TurboConfig config = TurboConfig.getInstance(tempDir.toFile());
        TurboCompressionService.initialize(config);
    }

    @AfterEach
    public void tearDown() {
        TurboCompressionService.resetInstance();
        TurboConfig.resetInstance();
    }

    @Test
    public void testRoundTripConversion() {
        // Create complex NBT data
        CompoundTag root = new CompoundTag();
        root.putString("Name", "TurboMC Player");
        root.putInt("Level", 100);
        root.putDouble("Health", 20.5);
        
        CompoundTag abilities = new CompoundTag();
        abilities.putBoolean("Flying", true);
        abilities.putFloat("Speed", 1.5f);
        root.put("Abilities", abilities);
        
        ListTag inventory = new ListTag();
        CompoundTag sword = new CompoundTag();
        sword.putString("id", "minecraft:diamond_sword");
        sword.putInt("Count", 1);
        inventory.add(sword);
        
        CompoundTag stone = new CompoundTag();
        stone.putString("id", "minecraft:stone");
        stone.putInt("Count", 64);
        inventory.add(stone);
        
        root.put("Inventory", inventory);
        
        // Convert to PackedBinary
        PackedBinaryNBT packed = NBTConverter.toPackedBinary(root);
        
        // Assert serialization works
        byte[] bytes = packed.toBytes();
        System.out.println("PackedBinary Size: " + bytes.length + " bytes");
        Assertions.assertTrue(bytes.length > 0);
        
        // Reconstruct from bytes
        PackedBinaryNBT unpacked = PackedBinaryNBT.fromBytes(bytes);
        
        // Convert back to NBT
        CompoundTag result = NBTConverter.fromPackedBinary(unpacked);
        
        // Assert structure equality
        Assertions.assertEquals(root.getString("Name"), result.getString("Name"));
        Assertions.assertEquals(root.getInt("Level"), result.getInt("Level"));
        
        CompoundTag resAbilities = result.getCompoundOrEmpty("Abilities");
        Assertions.assertEquals(abilities.getBoolean("Flying"), resAbilities.getBoolean("Flying"));
        
        ListTag resInv = (ListTag) result.get("Inventory");
        Assertions.assertEquals(2, resInv.size());
        
        System.out.println("Original: " + root);
        System.out.println("Result:   " + result);
    }
    
    @Test
    public void testStringDeduplication() {
        CompoundTag root = new CompoundTag();
        // Add duplicate keys/values
        root.putString("Key1", "DuplicateValue");
        root.putString("Key2", "DuplicateValue");
        root.putString("Key3", "UniqueValue");
        
        PackedBinaryNBT packed = NBTConverter.toPackedBinary(root);
        
        // Pool should contain: "Key1", "DuplicateValue", "Key2", "Key3", "UniqueValue"
        // Ideally deduplicates keys AND values if they are strings.
        // My converter adds keys to pool.
        // It adds string values to pool.
        
        // Pool size check
        System.out.println("Pool: " + packed.getStringPool());
        Assertions.assertTrue(packed.getStringPool().contains("DuplicateValue"));
        // Count occurrences in pool list (should be 1)
        long count = packed.getStringPool().stream().filter(s -> s.equals("DuplicateValue")).count();
        Assertions.assertEquals(1, count, "Duplicate strings should be deduplicated");
    }
}
