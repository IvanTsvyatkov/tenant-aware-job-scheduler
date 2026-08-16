package com.scheduler.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void testSetAndGetTenantId() {
        // Arrange
        String tenantId = "tenant1";

        // Act
        TenantContext.setTenantId(tenantId);
        String result = TenantContext.getTenantId();

        // Assert
        assertEquals(tenantId, result);
    }

    @Test
    void testGetTenantId_WhenNotSet() {
        // Act
        String result = TenantContext.getTenantId();

        // Assert
        assertNull(result, "Should return null when tenant not set");
    }

    @Test
    void testClear() {
        // Arrange
        TenantContext.setTenantId("tenant1");
        assertNotNull(TenantContext.getTenantId());

        // Act
        TenantContext.clear();

        // Assert
        assertNull(TenantContext.getTenantId(), "Tenant should be null after clear");
    }

    @Test
    void testSetTenantId_OverwritesPreviousValue() {
        // Arrange
        TenantContext.setTenantId("tenant1");

        // Act
        TenantContext.setTenantId("tenant2");
        String result = TenantContext.getTenantId();

        // Assert
        assertEquals("tenant2", result);
    }

    @Test
    void testTenantContext_IsThreadLocal() throws InterruptedException {
        // Arrange
        String mainThreadTenant = "main-tenant";
        String workerThreadTenant = "worker-tenant";
        List<String> results = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        // Act
        TenantContext.setTenantId(mainThreadTenant);

        Thread workerThread = new Thread(() -> {
            TenantContext.setTenantId(workerThreadTenant);
            results.add(TenantContext.getTenantId());
            latch.countDown();
        });

        workerThread.start();
        latch.await(5, TimeUnit.SECONDS);

        // Assert
        assertEquals(mainThreadTenant, TenantContext.getTenantId(),
            "Main thread should have its own tenant");
        assertEquals(workerThreadTenant, results.get(0),
            "Worker thread should have its own tenant");
    }

    @Test
    void testMultipleThreadsWithDifferentTenants() throws InterruptedException {
        // Arrange
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(threadCount);
        List<String> results = new ArrayList<>();

        // Act
        for (int i = 0; i < threadCount; i++) {
            final int tenantNum = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String tenantId = "tenant" + tenantNum;
                    TenantContext.setTenantId(tenantId);

                    // Simulate some work
                    Thread.sleep(10);

                    synchronized (results) {
                        results.add(TenantContext.getTenantId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    TenantContext.clear();
                    completeLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Start all threads
        completeLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert
        assertEquals(threadCount, results.size());
        for (int i = 0; i < threadCount; i++) {
            assertTrue(results.contains("tenant" + i),
                "Should contain tenant" + i);
        }
    }

    @Test
    void testClear_MultipleTimes() {
        // Act & Assert
        assertDoesNotThrow(() -> {
            TenantContext.clear();
            TenantContext.clear();
            TenantContext.clear();
        }, "Multiple clears should not throw exception");

        assertNull(TenantContext.getTenantId());
    }

    @Test
    void testSetTenantId_WithNull() {
        // Act
        TenantContext.setTenantId(null);
        String result = TenantContext.getTenantId();

        // Assert
        assertNull(result, "Setting null should work");
    }

    @Test
    void testSetTenantId_WithEmptyString() {
        // Arrange
        String emptyTenant = "";

        // Act
        TenantContext.setTenantId(emptyTenant);
        String result = TenantContext.getTenantId();

        // Assert
        assertEquals(emptyTenant, result);
    }

    @Test
    void testTenantContext_AfterThreadCompletion() throws InterruptedException {
        // Arrange
        final String[] workerResult = new String[1];
        Thread worker = new Thread(() -> {
            TenantContext.setTenantId("worker-tenant");
            workerResult[0] = TenantContext.getTenantId();
            TenantContext.clear();
        });

        // Act
        worker.start();
        worker.join();

        // Assert
        assertEquals("worker-tenant", workerResult[0]);
        assertNull(TenantContext.getTenantId(),
            "Main thread should not be affected by worker thread");
    }

    @Test
    void testTenantContext_WithSpecialCharacters() {
        // Arrange
        String specialTenant = "tenant-1_test@domain.com";

        // Act
        TenantContext.setTenantId(specialTenant);
        String result = TenantContext.getTenantId();

        // Assert
        assertEquals(specialTenant, result);
    }

    @Test
    void testTenantContext_SetClearSet() {
        // Arrange & Act
        TenantContext.setTenantId("tenant1");
        assertEquals("tenant1", TenantContext.getTenantId());

        TenantContext.clear();
        assertNull(TenantContext.getTenantId());

        TenantContext.setTenantId("tenant2");
        assertEquals("tenant2", TenantContext.getTenantId());
    }
}
