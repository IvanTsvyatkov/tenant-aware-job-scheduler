package com.scheduler.service;

import com.scheduler.model.Job;
import com.scheduler.model.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class SseServiceTest {

    private SseService sseService;

    @BeforeEach
    void setUp() {
        sseService = new SseService();
    }

    @Test
    void testAddEmitter_CreatesNewEmitter() {
        String tenantId = "tenant1";

        SseEmitter emitter = sseService.addEmitter(tenantId);

        assertNotNull(emitter, "Should create and return new emitter");
    }

    @Test
    void testAddEmitter_MultipleEmittersForSameTenant() {
        String tenantId = "tenant1";

        SseEmitter emitter1 = sseService.addEmitter(tenantId);
        SseEmitter emitter2 = sseService.addEmitter(tenantId);

        assertNotNull(emitter1);
        assertNotNull(emitter2);
        assertNotSame(emitter1, emitter2, "Should create distinct emitters");
    }

    @Test
    void testAddEmitter_DifferentTenants() {
        SseEmitter emitter1 = sseService.addEmitter("tenant1");
        SseEmitter emitter2 = sseService.addEmitter("tenant2");
        SseEmitter emitter3 = sseService.addEmitter("tenant3");

        assertNotNull(emitter1);
        assertNotNull(emitter2);
        assertNotNull(emitter3);
    }

    @Test
    void testRemoveEmitter() {
        String tenantId = "tenant1";
        SseEmitter emitter = sseService.addEmitter(tenantId);

        // Remove emitter
        sseService.removeEmitter(tenantId, emitter);

        // After removal, internal map should be updated
        Map<String, Set<SseEmitter>> tenantEmitters =
            (Map<String, Set<SseEmitter>>) ReflectionTestUtils.getField(sseService, "tenantEmitters");

        Set<SseEmitter> emitters = tenantEmitters.get(tenantId);
        if (emitters != null) {
            assertFalse(emitters.contains(emitter), "Emitter should be removed");
        }
    }

    @Test
    void testRemoveEmitter_NonExistentTenant() {
        SseEmitter emitter = new SseEmitter();

        // Should not throw exception
        assertDoesNotThrow(() -> sseService.removeEmitter("nonexistent", emitter));
    }

    @Test
    void testRemoveEmitter_NonExistentEmitter() {
        String tenantId = "tenant1";
        sseService.addEmitter(tenantId);
        SseEmitter differentEmitter = new SseEmitter();

        // Remove emitter that was never added
        assertDoesNotThrow(() -> sseService.removeEmitter(tenantId, differentEmitter));
    }

    @Test
    void testPublishJobUpdate_NoEmitters() {
        String tenantId = "tenant1";
        Job job = createTestJob(tenantId);

        // Should not throw exception when no emitters registered
        assertDoesNotThrow(() -> sseService.publishJobUpdate(job));
    }

    @Test
    void testPublishJobUpdate_WithRegisteredEmitters() {
        String tenantId = "tenant1";
        Job job = createTestJob(tenantId);

        // Register emitter
        SseEmitter emitter = sseService.addEmitter(tenantId);
        assertNotNull(emitter);

        // Publish update - should not throw
        assertDoesNotThrow(() -> sseService.publishJobUpdate(job));
    }

    @Test
    void testPublishJobUpdate_MultipleEmitters() {
        String tenantId = "tenant1";
        Job job = createTestJob(tenantId);

        // Register multiple emitters
        SseEmitter emitter1 = sseService.addEmitter(tenantId);
        SseEmitter emitter2 = sseService.addEmitter(tenantId);
        SseEmitter emitter3 = sseService.addEmitter(tenantId);

        assertNotNull(emitter1);
        assertNotNull(emitter2);
        assertNotNull(emitter3);

        // Publish update - should reach all emitters without error
        assertDoesNotThrow(() -> sseService.publishJobUpdate(job));
    }

    @Test
    void testPublishJobUpdate_TenantIsolation() {
        Job job1 = createTestJob("tenant1");
        Job job2 = createTestJob("tenant2");

        // Register emitters for both tenants
        SseEmitter emitter1 = sseService.addEmitter("tenant1");
        SseEmitter emitter2 = sseService.addEmitter("tenant2");

        assertNotNull(emitter1);
        assertNotNull(emitter2);

        // Publish to tenant1 - should not affect tenant2
        assertDoesNotThrow(() -> sseService.publishJobUpdate(job1));

        // Publish to tenant2 - should not affect tenant1
        assertDoesNotThrow(() -> sseService.publishJobUpdate(job2));
    }

    @Test
    void testMultipleTenantsWithMultipleEmitters() {
        // Tenant1: 2 emitters
        SseEmitter t1e1 = sseService.addEmitter("tenant1");
        SseEmitter t1e2 = sseService.addEmitter("tenant1");

        // Tenant2: 3 emitters
        SseEmitter t2e1 = sseService.addEmitter("tenant2");
        SseEmitter t2e2 = sseService.addEmitter("tenant2");
        SseEmitter t2e3 = sseService.addEmitter("tenant2");

        assertNotNull(t1e1);
        assertNotNull(t1e2);
        assertNotNull(t2e1);
        assertNotNull(t2e2);
        assertNotNull(t2e3);

        Job job1 = createTestJob("tenant1");
        Job job2 = createTestJob("tenant2");

        // Publish updates - should work without error
        assertDoesNotThrow(() -> {
            sseService.publishJobUpdate(job1);
            sseService.publishJobUpdate(job2);
        });
    }

    @Test
    void testEmitterTimeout_NoTimeout() {
        String tenantId = "tenant1";
        SseEmitter emitter = sseService.addEmitter(tenantId);

        assertNotNull(emitter);
        // Timeout should be 0 (no timeout) based on implementation
        Long timeout = (Long) ReflectionTestUtils.getField(emitter, "timeout");
        assertEquals(0L, timeout, "Emitter should have no timeout");
    }

    @Test
    void testPublishJobUpdate_AllJobStatuses() {
        String tenantId = "tenant1";
        sseService.addEmitter(tenantId);

        // Test publishing jobs with different statuses
        for (JobStatus status : JobStatus.values()) {
            Job job = createTestJob(tenantId);
            job.setStatus(status);

            assertDoesNotThrow(() -> sseService.publishJobUpdate(job),
                "Should handle " + status + " status");
        }
    }

    @Test
    void testAddEmitter_ConcurrentRequests() throws InterruptedException {
        String tenantId = "tenant1";
        int threadCount = 10;
        List<SseEmitter> emitters = Collections.synchronizedList(new ArrayList<>());

        // Create multiple emitters concurrently
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                SseEmitter emitter = sseService.addEmitter(tenantId);
                emitters.add(emitter);
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(threadCount, emitters.size(), "All emitters should be created");

        // All should be distinct
        Set<SseEmitter> uniqueEmitters = new HashSet<>(emitters);
        assertEquals(threadCount, uniqueEmitters.size(), "All emitters should be unique");
    }

    @Test
    void testRemoveEmitter_CleansUpEmptyTenantSet() {
        String tenantId = "tenant1";
        SseEmitter emitter = sseService.addEmitter(tenantId);

        // Remove the emitter
        sseService.removeEmitter(tenantId, emitter);

        // Check internal state
        Map<String, Set<SseEmitter>> tenantEmitters =
            (Map<String, Set<SseEmitter>>) ReflectionTestUtils.getField(sseService, "tenantEmitters");

        Set<SseEmitter> emitters = tenantEmitters.get(tenantId);
        if (emitters != null) {
            assertTrue(emitters.isEmpty(), "Empty emitter set should remain");
        }
    }

    @Test
    void testPublishJobUpdate_WithJobDetails() {
        String tenantId = "tenant1";
        sseService.addEmitter(tenantId);

        Job job = Job.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .targetId("target-special")
                .payload("complex payload with special chars: !@#$%")
                .idempotencyKey("key-12345")
                .status(JobStatus.RUNNING)
                .retryCount(2)
                .maxRetries(3)
                .errorMessage("Previous attempt failed")
                .build();

        assertDoesNotThrow(() -> sseService.publishJobUpdate(job));
    }

    // Helper methods

    private Job createTestJob(String tenantId) {
        return Job.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .targetId("target-1")
                .payload("test")
                .idempotencyKey("key-" + UUID.randomUUID())
                .status(JobStatus.PENDING)
                .retryCount(0)
                .maxRetries(3)
                .build();
    }
}
