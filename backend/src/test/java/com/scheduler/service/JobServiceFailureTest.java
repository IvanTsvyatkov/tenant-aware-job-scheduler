package com.scheduler.service;

import com.scheduler.config.TenantContext;
import com.scheduler.dto.JobRequest;
import com.scheduler.dto.JobResponse;
import com.scheduler.model.Job;
import com.scheduler.model.JobStatus;
import com.scheduler.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Enhanced JobService tests with comprehensive failure scenarios
 */
@ExtendWith(MockitoExtension.class)
class JobServiceFailureTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private FlywayMigrationService migrationService;

    @Mock
    private jakarta.persistence.EntityManager entityManager;

    @Mock
    private jakarta.persistence.Query query;

    @Mock
    private SseService sseService;

    @InjectMocks
    private JobService jobService;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.executeUpdate()).thenReturn(0);
    }

    // ========== Idempotency Race Condition Tests ==========
    // The DataIntegrityViolation recovery path (constraint violation -> re-fetch
    // existing job) is covered by
    // JobServiceTest#testConcurrentDuplicateRequests_DataIntegrityViolation_ReturnsExistingJob.

    @Test
    void testCreateJob_IdempotencyRaceCondition_NoRecovery() {
        // Scenario: DataIntegrityViolationException but existing job not found
        String idempotencyKey = "lost-key";

        JobRequest request = JobRequest.builder()
                .tenantId("tenant1")
                .targetId("target-1")
                .payload("test")
                .idempotencyKey(idempotencyKey)
                .build();

        when(jobRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty());

        when(jobRepository.save(any(Job.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate key"));

        // Act & Assert - should propagate exception if can't find existing job
        assertThrows(DataIntegrityViolationException.class, () -> {
            jobService.createJob(request);
        });
    }

    // ========== Database Failure Tests ==========

    @Test
    void testCreateJob_DatabaseConnectionFailure() {
        // Scenario: Database is down or connection lost
        JobRequest request = JobRequest.builder()
                .tenantId("tenant1")
                .targetId("target-1")
                .payload("test")
                .idempotencyKey("key-123")
                .build();

        when(jobRepository.findByIdempotencyKey(anyString()))
                .thenThrow(new RuntimeException("Connection refused"));

        // Act & Assert
        assertThrows(RuntimeException.class, () -> {
            jobService.createJob(request);
        });
    }

    @Test
    void testCreateJob_RepositorySaveFailure() {
        // Scenario: Save operation fails
        JobRequest request = JobRequest.builder()
                .tenantId("tenant1")
                .targetId("target-1")
                .payload("test")
                .idempotencyKey("key-123")
                .build();

        when(jobRepository.findByIdempotencyKey(anyString()))
                .thenReturn(Optional.empty());
        when(jobRepository.save(any(Job.class)))
                .thenThrow(new RuntimeException("Disk full"));

        // Act & Assert
        assertThrows(RuntimeException.class, () -> {
            jobService.createJob(request);
        });
    }

    // ========== Schema Migration Failure Tests ==========

    @Test
    void testCreateJob_SchemaMigrationFailure() {
        // Scenario: Tenant schema creation/migration fails
        JobRequest request = JobRequest.builder()
                .tenantId("tenant1")
                .targetId("target-1")
                .payload("test")
                .idempotencyKey("key-123")
                .build();

        doThrow(new RuntimeException("Migration failed: Permission denied"))
                .when(migrationService).ensureTenantSchema(anyString());

        // Act & Assert
        assertThrows(RuntimeException.class, () -> {
            jobService.createJob(request);
        });

        verify(migrationService).ensureTenantSchema("tenant1");
    }

    // ========== Null/Invalid Input Tests ==========

    @Test
    void testCreateJob_NullRequest() {
        // Act & Assert
        assertThrows(NullPointerException.class, () -> {
            jobService.createJob(null);
        });
    }

    @Test
    void testCreateJob_NullTenantId() {
        // Scenario: Request with null tenant ID
        JobRequest request = JobRequest.builder()
                .tenantId(null)
                .targetId("target-1")
                .payload("test")
                .idempotencyKey("key-123")
                .build();

        // Act & Assert - should handle gracefully or throw specific exception
        assertThrows(Exception.class, () -> {
            jobService.createJob(request);
        });
    }

    @Test
    void testCreateJob_EmptyIdempotencyKey() {
        // Scenario: Empty idempotency key
        JobRequest request = JobRequest.builder()
                .tenantId("tenant1")
                .targetId("target-1")
                .payload("test")
                .idempotencyKey("")
                .build();

        when(jobRepository.findByIdempotencyKey(""))
                .thenReturn(Optional.empty());

        Job savedJob = Job.builder()
                .id(UUID.randomUUID())
                .idempotencyKey("")
                .build();

        when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

        // Act
        JobResponse response = jobService.createJob(request);

        // Assert - should handle empty key
        assertNotNull(response);
    }

    // ========== Retrieval Failure Tests ==========

    @Test
    void testGetJob_RepositoryThrowsException() {
        // Scenario: Database error during retrieval
        String tenantId = "tenant1";
        UUID jobId = UUID.randomUUID();

        when(jobRepository.findById(jobId))
                .thenThrow(new RuntimeException("Database connection lost"));

        // Act & Assert
        assertThrows(RuntimeException.class, () -> {
            jobService.getJob(tenantId, jobId);
        });
    }

    @Test
    void testGetJobsByTenant_RepositoryThrowsException() {
        // Scenario: Error listing all jobs
        String tenantId = "tenant1";

        when(jobRepository.findAll())
                .thenThrow(new RuntimeException("Query timeout"));

        // Act & Assert
        assertThrows(RuntimeException.class, () -> {
            jobService.getJobsByTenant(tenantId);
        });
    }


    // ========== SSE Failure Tests ==========

    @Test
    void testCreateJob_SsePublishFailure() {
        // Scenario: Job created but SSE notification fails
        JobRequest request = JobRequest.builder()
                .tenantId("tenant1")
                .targetId("target-1")
                .payload("test")
                .idempotencyKey("key-123")
                .build();

        Job savedJob = Job.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant1")
                .idempotencyKey("key-123")
                .status(JobStatus.PENDING)
                .build();

        when(jobRepository.findByIdempotencyKey(anyString()))
                .thenReturn(Optional.empty());
        when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

        doThrow(new RuntimeException("SSE connection broken"))
                .when(sseService).publishJobUpdate(any(Job.class));

        // Act & Assert - job should still be created even if SSE fails
        assertThrows(RuntimeException.class, () -> {
            jobService.createJob(request);
        });

        verify(jobRepository).save(any(Job.class));
        verify(sseService).publishJobUpdate(any(Job.class));
    }

    // ========== Concurrent Modification Tests ==========

    @Test
    void testCreateJob_MultipleConcurrentRequests() {
        // Scenario: Simulate multiple rapid requests with different keys
        String tenantId = "tenant1";

        for (int i = 0; i < 5; i++) {
            String key = "key-" + i;
            JobRequest request = JobRequest.builder()
                    .tenantId(tenantId)
                    .targetId("target-1")
                    .payload("test-" + i)
                    .idempotencyKey(key)
                    .build();

            Job savedJob = Job.builder()
                    .id(UUID.randomUUID())
                    .idempotencyKey(key)
                    .tenantId(tenantId)
                    .build();

            lenient().when(jobRepository.findByIdempotencyKey(key))
                    .thenReturn(Optional.empty());
            lenient().when(jobRepository.save(any(Job.class)))
                    .thenReturn(savedJob);

            // Act
            JobResponse response = jobService.createJob(request);

            // Assert
            assertNotNull(response);
        }

        verify(jobRepository, atLeast(5)).save(any(Job.class));
    }

    // ========== Edge Case Tests ==========

    @Test
    void testGetJob_WithNullTenantId() {
        // Scenario: Null tenant ID in retrieval
        UUID jobId = UUID.randomUUID();

        Job job = Job.builder()
                .id(jobId)
                .tenantId("tenant1")
                .build();

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        // Act
        Optional<JobResponse> response = jobService.getJob(null, jobId);

        // Assert - should still work (tenant context handles isolation)
        assertTrue(response.isPresent());
    }

    @Test
    void testCreateJob_VeryLongPayload() {
        // Scenario: Extremely long payload
        String longPayload = "a".repeat(10000);

        JobRequest request = JobRequest.builder()
                .tenantId("tenant1")
                .targetId("target-1")
                .payload(longPayload)
                .idempotencyKey("key-123")
                .build();

        Job savedJob = Job.builder()
                .id(UUID.randomUUID())
                .payload(longPayload)
                .build();

        when(jobRepository.findByIdempotencyKey(anyString()))
                .thenReturn(Optional.empty());
        when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

        // Act
        JobResponse response = jobService.createJob(request);

        // Assert
        assertNotNull(response);
        assertEquals(longPayload, response.getPayload());
    }

    @Test
    void testCreateJob_SpecialCharactersInData() {
        // Scenario: Special characters in various fields
        String specialChars = "!@#$%^&*()_+-=[]{}|;':\",./<>?";

        JobRequest request = JobRequest.builder()
                .tenantId("tenant-special!@#")
                .targetId("target_$%^")
                .payload(specialChars)
                .idempotencyKey("key_special_chars")
                .build();

        Job savedJob = Job.builder()
                .id(UUID.randomUUID())
                .tenantId("tenant-special!@#")
                .payload(specialChars)
                .build();

        when(jobRepository.findByIdempotencyKey(anyString()))
                .thenReturn(Optional.empty());
        when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

        // Act
        JobResponse response = jobService.createJob(request);

        // Assert
        assertNotNull(response);
        assertEquals(specialChars, response.getPayload());
    }
}
