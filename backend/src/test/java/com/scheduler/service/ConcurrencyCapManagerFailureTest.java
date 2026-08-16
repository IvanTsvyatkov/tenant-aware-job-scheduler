package com.scheduler.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Failure and edge case tests for ConcurrencyCapManager
 */
class ConcurrencyCapManagerFailureTest {

    private ConcurrencyCapManager capManager;

    @BeforeEach
    void setUp() {
        capManager = new ConcurrencyCapManager(5, 2, 1);
    }

    // ========== Capacity Exhaustion Tests ==========
    // Basic global/tenant/target cap enforcement is covered by
    // ConcurrencyCapManagerTest; this file focuses on failure/edge cases.

    // ========== Release Without Acquire Tests ==========

    @Test
    void testRelease_WithoutAcquire() {
        // Release without acquiring
        assertDoesNotThrow(() -> {
            capManager.release("tenant1", "target1");
        }, "Release without acquire should not throw");
    }

    @Test
    void testRelease_MultipleTimesForSameJob() {
        // Acquire once
        assertTrue(capManager.tryAcquire("tenant1", "target1"));

        // Release multiple times
        assertDoesNotThrow(() -> {
            capManager.release("tenant1", "target1");
            capManager.release("tenant1", "target1");
            capManager.release("tenant1", "target1");
        }, "Multiple releases should not throw");
    }

    // ========== Null/Invalid Input Tests ==========

    @Test
    void testTryAcquire_NullTenantId() {
        // Act & Assert
        assertDoesNotThrow(() -> {
            capManager.tryAcquire(null, "target1");
        }, "Should handle null tenant ID");
    }

    @Test
    void testTryAcquire_NullTargetId() {
        // Act & Assert
        assertDoesNotThrow(() -> {
            capManager.tryAcquire("tenant1", null);
        }, "Should handle null target ID");
    }

    @Test
    void testTryAcquire_EmptyStrings() {
        // Act
        boolean result = capManager.tryAcquire("", "");

        // Assert - should handle empty strings
        assertNotNull(result);
    }

    @Test
    void testRelease_NullTenantId() {
        // Act & Assert
        assertDoesNotThrow(() -> {
            capManager.release(null, "target1");
        }, "Release with null tenant should not throw");
    }

    @Test
    void testRelease_NullTargetId() {
        // Act & Assert
        assertDoesNotThrow(() -> {
            capManager.release("tenant1", null);
        }, "Release with null target should not throw");
    }

    // ========== Concurrent Access Stress Tests ==========

    @Test
    void testConcurrentAcquire_RaceCondition() throws InterruptedException {
        // Scenario: Many threads trying to acquire at same time
        int threadCount = 20;
        int globalCap = 5;
        ConcurrencyCapManager manager = new ConcurrencyCapManager(globalCap, 10, 10);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);

        List<Boolean> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // All threads start at same time
                    boolean acquired = manager.tryAcquire("tenant" + threadNum, "target" + threadNum);
                    results.add(acquired);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        completeLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert - exactly globalCap should succeed
        long successCount = results.stream().filter(b -> b).count();
        assertEquals(globalCap, successCount,
            "Exactly " + globalCap + " threads should acquire permits");
    }

    @Test
    void testConcurrentReleaseAndAcquire() throws InterruptedException {
        // Scenario: Threads constantly acquiring and releasing
        ConcurrencyCapManager manager = new ConcurrencyCapManager(3, 3, 3);
        int operationCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(10);

        CountDownLatch completeLatch = new CountDownLatch(operationCount);
        List<Exception> exceptions = new CopyOnWriteArrayList<>();

        for (int i = 0; i < operationCount; i++) {
            final int opNum = i;
            executor.submit(() -> {
                try {
                    String tenant = "tenant" + (opNum % 3);
                    String target = "target" + (opNum % 3);

                    if (manager.tryAcquire(tenant, target)) {
                        Thread.sleep(10); // Simulate work
                        manager.release(tenant, target);
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    completeLatch.countDown();
                }
            });
        }

        completeLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert - no exceptions should occur
        assertTrue(exceptions.isEmpty(),
            "No exceptions should occur during concurrent operations");
    }

    // ========== Deadlock Prevention Tests ==========

    @Test
    void testNoDeadlock_DifferentAcquisitionOrder() throws InterruptedException {
        // Scenario: Two threads trying to acquire in different order
        ConcurrencyCapManager manager = new ConcurrencyCapManager(10, 10, 10);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch completeLatch = new CountDownLatch(2);
        List<Boolean> results = new CopyOnWriteArrayList<>();

        // Thread 1: tenant1 -> tenant2
        executor.submit(() -> {
            try {
                results.add(manager.tryAcquire("tenant1", "target1"));
                Thread.sleep(50);
                results.add(manager.tryAcquire("tenant2", "target2"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completeLatch.countDown();
            }
        });

        // Thread 2: tenant2 -> tenant1
        executor.submit(() -> {
            try {
                results.add(manager.tryAcquire("tenant2", "target3"));
                Thread.sleep(50);
                results.add(manager.tryAcquire("tenant1", "target4"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                completeLatch.countDown();
            }
        });

        boolean completed = completeLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert - should complete without deadlock
        assertTrue(completed, "Should complete without deadlock");
        assertEquals(4, results.size());
    }

    // ========== Edge Case: Zero Capacity ==========

    @Test
    void testZeroGlobalCapacity() {
        // Scenario: Global capacity set to 0
        ConcurrencyCapManager zeroCapManager = new ConcurrencyCapManager(0, 10, 10);

        // Act
        boolean result = zeroCapManager.tryAcquire("tenant1", "target1");

        // Assert - should always fail
        assertFalse(result, "Should fail when global capacity is 0");
    }

    @Test
    void testZeroTenantCapacity() {
        // Scenario: Tenant capacity set to 0
        ConcurrencyCapManager zeroTenantCapManager = new ConcurrencyCapManager(10, 0, 10);

        // Act
        boolean result = zeroTenantCapManager.tryAcquire("tenant1", "target1");

        // Assert - should fail
        assertFalse(result, "Should fail when tenant capacity is 0");
    }

    @Test
    void testZeroTargetCapacity() {
        // Scenario: Target capacity set to 0
        ConcurrencyCapManager zeroTargetCapManager = new ConcurrencyCapManager(10, 10, 0);

        // Act
        boolean result = zeroTargetCapManager.tryAcquire("tenant1", "target1");

        // Assert - should fail
        assertFalse(result, "Should fail when target capacity is 0");
    }

    // ========== Capacity Recovery Tests ==========

    @Test
    void testCapacityRecovery_AfterReleases() {
        // Exhaust capacity
        assertTrue(capManager.tryAcquire("tenant1", "target1"));
        assertTrue(capManager.tryAcquire("tenant2", "target2"));
        assertTrue(capManager.tryAcquire("tenant3", "target3"));
        assertTrue(capManager.tryAcquire("tenant4", "target4"));
        assertTrue(capManager.tryAcquire("tenant5", "target5"));

        // All exhausted
        assertFalse(capManager.tryAcquire("tenant6", "target6"));

        // Release some
        capManager.release("tenant1", "target1");
        capManager.release("tenant2", "target2");

        // Should be able to acquire again
        assertTrue(capManager.tryAcquire("tenant6", "target6"));
        assertTrue(capManager.tryAcquire("tenant7", "target7"));
    }

    // ========== Large Number Tests ==========

    @Test
    void testVeryLargeCapacity() {
        // Scenario: Very large capacity values
        ConcurrencyCapManager largeCapManager =
            new ConcurrencyCapManager(100000, 10000, 1000);

        // Act
        boolean result = largeCapManager.tryAcquire("tenant1", "target1");

        // Assert
        assertTrue(result, "Should work with large capacity values");
    }

    @Test
    void testManyDifferentTenantsAndTargets() {
        // Scenario: Many unique tenants and targets
        ConcurrencyCapManager manager = new ConcurrencyCapManager(1000, 10, 10);

        for (int i = 0; i < 100; i++) {
            assertTrue(manager.tryAcquire("tenant" + i, "target" + i),
                "Should handle many different tenants");
        }

        assertEquals(900, manager.getGlobalAvailable(),
            "Should have 900 remaining after 100 acquisitions");
    }

    // ========== State Consistency Tests ==========

    @Test
    void testAvailableCapacity_ConsistentWithAcquireRelease() {
        // Initial state
        assertEquals(5, capManager.getGlobalAvailable());

        // Acquire
        capManager.tryAcquire("tenant1", "target1");
        assertEquals(4, capManager.getGlobalAvailable());

        capManager.tryAcquire("tenant2", "target2");
        assertEquals(3, capManager.getGlobalAvailable());

        // Release
        capManager.release("tenant1", "target1");
        assertEquals(4, capManager.getGlobalAvailable());

        // Acquire again
        capManager.tryAcquire("tenant3", "target3");
        assertEquals(3, capManager.getGlobalAvailable());
    }
}
