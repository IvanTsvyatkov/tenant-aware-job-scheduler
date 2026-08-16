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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

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
        // Mock the search_path query
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(0);
    }

    @Test
    void testCreateJob_Success() {
        // Arrange
        String tenantId = "tenant1";
        JobRequest request = JobRequest.builder()
                .tenantId(tenantId)
                .targetId("target-1")
                .payload("test payload")
                .idempotencyKey("unique-key-123")
                .build();

        Job savedJob = Job.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .targetId("target-1")
                .payload("test payload")
                .idempotencyKey("unique-key-123")
                .status(JobStatus.PENDING)
                .retryCount(0)
                .maxRetries(3)
                .build();

        when(jobRepository.findByIdempotencyKey(request.getIdempotencyKey()))
                .thenReturn(Optional.empty());
        when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

        // Act
        JobResponse response = jobService.createJob(request);

        // Assert
        assertNotNull(response);
        assertEquals(tenantId, response.getTenantId());
        assertEquals("target-1", response.getTargetId());
        assertEquals("unique-key-123", response.getIdempotencyKey());
        assertEquals(JobStatus.PENDING, response.getStatus());

        verify(migrationService).ensureTenantSchema(tenantId);
        verify(jobRepository).findByIdempotencyKey("unique-key-123");
        verify(jobRepository).save(any(Job.class));
    }

    @Test
    void testCreateJob_IdempotencyKeyExists_ReturnsSameJob() {
        // Arrange
        String tenantId = "tenant1";
        JobRequest request = JobRequest.builder()
                .tenantId(tenantId)
                .targetId("target-1")
                .payload("test payload")
                .idempotencyKey("duplicate-key")
                .build();

        Job existingJob = Job.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .targetId("target-1")
                .payload("original payload")
                .idempotencyKey("duplicate-key")
                .status(JobStatus.SUCCEEDED)
                .retryCount(0)
                .maxRetries(3)
                .build();

        when(jobRepository.findByIdempotencyKey("duplicate-key"))
                .thenReturn(Optional.of(existingJob));

        // Act
        JobResponse response = jobService.createJob(request);

        // Assert
        assertNotNull(response);
        assertEquals(existingJob.getId(), response.getId());
        assertEquals(JobStatus.SUCCEEDED, response.getStatus());

        verify(migrationService).ensureTenantSchema(tenantId);
        verify(jobRepository).findByIdempotencyKey("duplicate-key");
        verify(jobRepository, never()).save(any(Job.class)); // Should not create new job
    }


    @Test
    void testGetJobsByTenant() {
        String tenantId = "tenant1";
        List<Job> jobs = Arrays.asList(
                createJob(UUID.randomUUID(), tenantId, "target-1", JobStatus.PENDING),
                createJob(UUID.randomUUID(), tenantId, "target-2", JobStatus.RUNNING)
        );

        when(jobRepository.findAll()).thenReturn(jobs);

        List<JobResponse> responses = jobService.getJobsByTenant(tenantId);

        assertNotNull(responses);
        assertEquals(2, responses.size());
        assertEquals(tenantId, responses.get(0).getTenantId());
        assertEquals(JobStatus.PENDING, responses.get(0).getStatus());
    }

    @Test
    void testGetJobsByTenant_Empty() {
        String tenantId = "tenant1";
        when(jobRepository.findAll()).thenReturn(List.of());

        List<JobResponse> responses = jobService.getJobsByTenant(tenantId);

        assertNotNull(responses);
        assertTrue(responses.isEmpty());
    }

    @Test
    void testGetJob_Found() {
        String tenantId = "tenant1";
        UUID jobId = UUID.randomUUID();
        Job job = createJob(jobId, tenantId, "target-1", JobStatus.SUCCEEDED);

        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        Optional<JobResponse> response = jobService.getJob(tenantId, jobId);

        assertTrue(response.isPresent());
        assertEquals(jobId, response.get().getId());
        assertEquals(tenantId, response.get().getTenantId());
        assertEquals(JobStatus.SUCCEEDED, response.get().getStatus());
    }

    @Test
    void testGetJob_NotFound() {
        String tenantId = "tenant1";
        UUID jobId = UUID.randomUUID();

        when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

        Optional<JobResponse> response = jobService.getJob(tenantId, jobId);

        assertFalse(response.isPresent());
    }

    @Test
    void testCreateJob_WithDifferentTargets() {
        String tenantId = "tenant1";

        for (int i = 1; i <= 3; i++) {
            String targetId = "target-" + i;
            JobRequest request = JobRequest.builder()
                    .tenantId(tenantId)
                    .targetId(targetId)
                    .payload("test")
                    .idempotencyKey("key-" + i)
                    .build();

            Job savedJob = createJob(UUID.randomUUID(), tenantId, targetId, JobStatus.PENDING);
            when(jobRepository.findByIdempotencyKey("key-" + i)).thenReturn(Optional.empty());
            when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

            JobResponse response = jobService.createJob(request);

            assertEquals(targetId, response.getTargetId());
        }

        verify(jobRepository, times(3)).save(any(Job.class));
    }

    /**
     * Verifies that concurrent duplicate requests with the same idempotency key
     * never double-enqueue a job: only one save() is issued and every caller
     * receives the same job ID.
     */
    @Test
    void testConcurrentDuplicateRequests_SameIdempotencyKey_NeverDoubleEnqueue() throws InterruptedException, ExecutionException {
        String tenantId = "tenant1";
        String idempotencyKey = "concurrent-key-999";
        UUID existingJobId = UUID.randomUUID();

        Job savedJob = Job.builder()
                .id(existingJobId)
                .tenantId(tenantId)
                .targetId("target-1")
                .payload("payload")
                .idempotencyKey(idempotencyKey)
                .status(JobStatus.PENDING)
                .retryCount(0)
                .maxRetries(3)
                .build();

        // First call finds nothing (pre-existing), subsequent calls (from other threads) find the job.
        // This simulates one thread "winning" the race and the others seeing the row afterwards.
        AtomicInteger findCallCount = new AtomicInteger(0);
        when(jobRepository.findByIdempotencyKey(idempotencyKey)).thenAnswer(inv -> {
            int callIndex = findCallCount.getAndIncrement();
            return callIndex == 0 ? Optional.empty() : Optional.of(savedJob);
        });
        when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Future<JobResponse>> futures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        JobRequest request = JobRequest.builder()
                .tenantId(tenantId)
                .targetId("target-1")
                .payload("payload")
                .idempotencyKey(idempotencyKey)
                .build();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await(); // all threads start simultaneously
                try {
                    return jobService.createJob(request);
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown(); // release all threads at once
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should finish within 10s");
        executor.shutdown();

        // All responses must carry the same job ID — no double-enqueue
        for (Future<JobResponse> future : futures) {
            JobResponse response = future.get();
            assertNotNull(response);
            assertEquals(existingJobId, response.getId(),
                    "Every concurrent caller must receive the same job ID");
        }

        // save() must have been called at most once — the first thread to pass the empty check
        verify(jobRepository, atMostOnce()).save(any(Job.class));
    }

    /**
     * Verifies the DataIntegrityViolationException fallback path:
     * when two threads race past the empty-check simultaneously and one of them
     * hits a DB unique-constraint violation, the service recovers and returns
     * the already-persisted job instead of propagating the exception.
     */
    @Test
    void testConcurrentDuplicateRequests_DataIntegrityViolation_ReturnsExistingJob() {
        String tenantId = "tenant1";
        String idempotencyKey = "race-condition-key";
        UUID existingJobId = UUID.randomUUID();

        Job existingJob = Job.builder()
                .id(existingJobId)
                .tenantId(tenantId)
                .targetId("target-1")
                .payload("original")
                .idempotencyKey(idempotencyKey)
                .status(JobStatus.PENDING)
                .retryCount(0)
                .maxRetries(3)
                .build();

        // Simulate: check returns empty (both threads passed the guard), save throws,
        // then the retry-find returns the existing row.
        when(jobRepository.findByIdempotencyKey(idempotencyKey))
                .thenReturn(Optional.empty())          // initial check
                .thenReturn(Optional.of(existingJob)); // post-exception re-fetch
        when(jobRepository.save(any(Job.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint violation"));

        JobRequest request = JobRequest.builder()
                .tenantId(tenantId)
                .targetId("target-1")
                .payload("duplicate payload")
                .idempotencyKey(idempotencyKey)
                .build();

        // Should NOT throw; should recover and return the existing job
        JobResponse response = assertDoesNotThrow(() -> jobService.createJob(request));

        assertNotNull(response);
        assertEquals(existingJobId, response.getId(),
                "After a race-condition constraint violation the existing job must be returned");
        assertEquals(JobStatus.PENDING, response.getStatus());

        verify(jobRepository, times(2)).findByIdempotencyKey(idempotencyKey);
        verify(jobRepository, times(1)).save(any(Job.class));
    }

    // Helper method
    private Job createJob(UUID id, String tenantId, String targetId, JobStatus status) {
        return Job.builder()
                .id(id)
                .tenantId(tenantId)
                .targetId(targetId)
                .payload("test")
                .idempotencyKey("key-" + id)
                .status(status)
                .retryCount(0)
                .maxRetries(3)
                .build();
    }
}
