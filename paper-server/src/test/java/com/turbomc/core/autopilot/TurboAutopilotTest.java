package com.turbomc.core.autopilot;

import com.turbomc.config.TurboConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

public class TurboAutopilotTest {

    private Path testDir;
    private TurboAutopilot autopilot;

    @BeforeEach
    void setUp() throws IOException {
        testDir = Files.createTempDirectory("autopilot_test");
        Path configFile = testDir.resolve("turbo.toml");
        Files.writeString(configFile, "[world.generation]\nhyperview-radius = 128\n[autopilot]\nenabled = true");

        TurboConfig.resetInstance();
        TurboConfig.getInstance(testDir.toFile());

        HardwareSentry.resetInstance();
        HealthMonitor.resetInstance();
        TurboAutopilot.resetInstance();

        autopilot = TurboAutopilot.getInstance();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(testDir)) {
            Files.walk(testDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    @Test
    void testRadiusClamping() throws Exception {
        System.out.println("=== AUTOPILOT RADIUS CLAMPING TEST ===");
        
        // Force HardwareSentry to LOW_END (maxRadius = 16)
        setGrade(HardwareSentry.ResourceGrade.LOW_END);
        
        // Requested is 128 (from setup config)
        autopilot.tick();
        
        assertEquals(16, autopilot.getEffectiveHyperViewRadius(), 
            "Radius should be clamped to 16 for LOW_END hardware");
        
        // Change to HIGH_PERFORMANCE (maxRadius = 96)
        setGrade(HardwareSentry.ResourceGrade.HIGH_PERFORMANCE);
        autopilot.tick();
        
        assertEquals(96, autopilot.getEffectiveHyperViewRadius(), 
            "Radius should be clamped to 96 for HIGH_PERFORMANCE hardware");

        System.out.println("✓ Radius clamping verified");
    }

    @Test
    void testCriticalThrottling() throws Exception {
        System.out.println("=== AUTOPILOT CRITICAL THROTTLING TEST ===");
        
        setGrade(HardwareSentry.ResourceGrade.HIGH_PERFORMANCE); // Max 96
        
        // Inject Critical health (150ms MSPT, 5 TPS)
        setHealth(150.0, 5.0);
        
        autopilot.tick();
        
        assertEquals(8, autopilot.getEffectiveHyperViewRadius(), 
            "Radius should drop to 8 during critical load");
            
        System.out.println("✓ Critical throttling verified");
    }

    @Test
    void testStrugglingThrottling() throws Exception {
        System.out.println("=== AUTOPILOT STRUGGLING THROTTLING TEST ===");
        
        setGrade(HardwareSentry.ResourceGrade.HIGH_PERFORMANCE); // Max 96
        
        // Inject Struggling health (60ms MSPT, 14 TPS)
        setHealth(60.0, 14.0);
        
        autopilot.tick();
        
        // targetRadius = Min(requested(128), maxSafe(96)) = 96
        // Struggling: targetRadius = Max(16, 96 / 2) = 48
        assertEquals(48, autopilot.getEffectiveHyperViewRadius(), 
            "Radius should be halved during struggling load");
            
        System.out.println("✓ Struggling throttling verified");
    }

    @Test
    void testRecovery() throws Exception {
        System.out.println("=== AUTOPILOT RECOVERY TEST ===");
        
        setGrade(HardwareSentry.ResourceGrade.HIGH_PERFORMANCE); // Max 96
        
        // 1. Go to critical
        setHealth(150.0, 5.0);
        autopilot.tick();
        assertEquals(8, autopilot.getEffectiveHyperViewRadius());
        
        // 2. Recover to healthy
        setHealth(20.0, 20.0);
        autopilot.tick();
        
        assertEquals(96, autopilot.getEffectiveHyperViewRadius(), 
            "Radius should recover to clamped limit after health improves");
            
        System.out.println("✓ Recovery verified");
    }

    @Test
    void testRequestedRadiusUpdate() throws Exception {
        System.out.println("=== AUTOPILOT REQUESTED RADIUS TEST ===");
        
        setGrade(HardwareSentry.ResourceGrade.HIGH_PERFORMANCE); // Max 96
        
        // Update requested to 32
        // Note: For now TurboConfig doesn't have a public 'set' for testing easily without writing to file
        // but TurboAutopilot.setRequestedRadius calls tick()
        // We simulate the requested change by changing the config and calling the method
        
        // Actually, let's just test that setRequestedRadius triggers a re-tick
        autopilot.setRequestedRadius(32); 
        // We can't easily verify the requested value inside autopilot without more reflection
        // but we can verify it still respects clamping
        
        autopilot.setRequestedRadius(500);
        assertEquals(96, autopilot.getEffectiveHyperViewRadius(), 
            "Manually requested radius should still be clamped");
            
        System.out.println("✓ Requested radius logic verified");
    }

    // Helper methods using reflection to manipulate singletons

    private void setGrade(HardwareSentry.ResourceGrade grade) throws Exception {
        HardwareSentry sentry = HardwareSentry.getInstance();
        Field field = HardwareSentry.class.getDeclaredField("grade");
        field.setAccessible(true);
        field.set(sentry, grade);
    }

    private void setHealth(double mspt, double tps) throws Exception {
        HealthMonitor health = HealthMonitor.getInstance();
        Field field = HealthMonitor.class.getDeclaredField("lastSnapshot");
        field.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<HealthMonitor.HealthSnapshot> ref = 
            (java.util.concurrent.atomic.AtomicReference<HealthMonitor.HealthSnapshot>) field.get(health);
        ref.set(new HealthMonitor.HealthSnapshot(mspt, tps));
    }
}
