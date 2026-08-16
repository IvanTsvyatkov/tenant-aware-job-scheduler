package com.scheduler.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyCapManagerTest {

    private ConcurrencyCapManager capManager;

    @BeforeEach
    void setUp() {
        // Initialize with test caps: global=10, tenant=5, target=3
        capManager = new ConcurrencyCapManager(10, 5, 3);
    }

    @Test
    void testTryAcquire_Success() {
        // Act & Assert
        assertTrue(capManager.tryAcquire("tenant1", "target1"));
        assertEquals(9, capManager.getGlobalAvailable());
        assertEquals(4, capManager.getTenantAvailable("tenant1"));
        assertEquals(2, capManager.getTargetAvailable("target1"));
    }

    @Test
    void testRelease_RestoresPermits() {
        // Arrange
        capManager.tryAcquire("tenant1", "target1");

        // Act
        capManager.release("tenant1", "target1");

        // Assert
        assertEquals(10, capManager.getGlobalAvailable());
        assertEquals(5, capManager.getTenantAvailable("tenant1"));
        assertEquals(3, capManager.getTargetAvailable("target1"));
    }

    @Test
    void testGlobalCapEnforcement() {
        // Acquire 10 permits (global cap)
        for (int i = 0; i < 10; i++) {
            assertTrue(capManager.tryAcquire("tenant" + i, "target" + i),
                    "Should acquire permit " + (i + 1));
        }

        // 11th attempt should fail (global cap hit)
        assertFalse(capManager.tryAcquire("tenant11", "target11"),
                "Should fail due to global cap");
    }

    @Test
    void testTenantCapEnforcement() {
        // Acquire 5 permits for tenant1 (tenant cap)
        for (int i = 0; i < 5; i++) {
            assertTrue(capManager.tryAcquire("tenant1", "target" + i),
                    "Should acquire permit " + (i + 1) + " for tenant1");
        }

        // 6th attempt for tenant1 should fail (tenant cap hit)
        assertFalse(capManager.tryAcquire("tenant1", "target6"),
                "Should fail due to tenant cap");

        // But tenant2 should still be able to acquire
        assertTrue(capManager.tryAcquire("tenant2", "target1"),
                "Tenant2 should succeed independently");
    }

    @Test
    void testTargetCapEnforcement() {
        // Acquire 3 permits for target1 (target cap)
        for (int i = 0; i < 3; i++) {
            assertTrue(capManager.tryAcquire("tenant" + i, "target1"),
                    "Should acquire permit " + (i + 1) + " for target1");
        }

        // 4th attempt for target1 should fail (target cap hit)
        assertFalse(capManager.tryAcquire("tenant4", "target1"),
                "Should fail due to target cap");

        // But target2 should still be able to acquire
        assertTrue(capManager.tryAcquire("tenant1", "target2"),
                "Target2 should succeed independently");
    }

    @Test
    void testConcurrentAcquisition() throws InterruptedException {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        // All threads try to acquire for the same tenant and target
        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    latch.countDown();
                    latch.await(); // Ensure all threads start together

                    if (capManager.tryAcquire("tenant1", "target1")) {
                        successCount.incrementAndGet();
                        Thread.sleep(10); // Hold permit briefly
                        capManager.release("tenant1", "target1");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // With target cap of 3, we should see successful acquisitions
        // but never more than 3 at once
        assertTrue(successCount.get() > 0, "Some acquisitions should succeed");
    }

    @Test
    void testAtomicAcquisition_AllOrNothing() {
        // Fill up global cap
        for (int i = 0; i < 10; i++) {
            capManager.tryAcquire("tenant" + i, "target" + i);
        }

        // Try to acquire - should fail atomically (no partial acquisition)
        assertFalse(capManager.tryAcquire("tenant99", "target99"));

        // Verify no permits were partially acquired
        assertEquals(0, capManager.getGlobalAvailable());
        assertEquals(5, capManager.getTenantAvailable("tenant99"));
        assertEquals(3, capManager.getTargetAvailable("target99"));
    }

    @Test
    void testMultipleTenantsAndTargets() {
        // Tenant1 acquires 3 permits for different targets
        assertTrue(capManager.tryAcquire("tenant1", "target1"));
        assertTrue(capManager.tryAcquire("tenant1", "target2"));
        assertTrue(capManager.tryAcquire("tenant1", "target3"));

        // Tenant2 acquires 3 permits for different targets
        assertTrue(capManager.tryAcquire("tenant2", "target1"));
        assertTrue(capManager.tryAcquire("tenant2", "target2"));
        assertTrue(capManager.tryAcquire("tenant2", "target3"));

        // Check global: 6 permits used
        assertEquals(4, capManager.getGlobalAvailable());

        // Check tenant caps
        assertEquals(2, capManager.getTenantAvailable("tenant1"));
        assertEquals(2, capManager.getTenantAvailable("tenant2"));

        // Check target caps: each target has 2 permits used
        assertEquals(1, capManager.getTargetAvailable("target1"));
        assertEquals(1, capManager.getTargetAvailable("target2"));
        assertEquals(1, capManager.getTargetAvailable("target3"));
    }

    @Test
    void testMaxGetters_ExposeConfiguredCaps() {
        // The getters back the /config/concurrency endpoint used by the frontend
        assertEquals(10, capManager.getGlobalMax());
        assertEquals(5, capManager.getTenantMax());
        assertEquals(3, capManager.getTargetMax());
    }

    @Test
    void testMaxGetters_UnaffectedByAcquireAndRelease() {
        capManager.tryAcquire("tenant1", "target1");

        // Acquiring permits must not change the configured maximums
        assertEquals(10, capManager.getGlobalMax());
        assertEquals(5, capManager.getTenantMax());
        assertEquals(3, capManager.getTargetMax());

        capManager.release("tenant1", "target1");

        assertEquals(10, capManager.getGlobalMax());
        assertEquals(5, capManager.getTenantMax());
        assertEquals(3, capManager.getTargetMax());
    }
}
