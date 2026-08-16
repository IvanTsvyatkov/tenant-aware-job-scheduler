package com.scheduler.service;

import com.scheduler.config.TenantContext;
import com.scheduler.model.Job;
import com.scheduler.model.JobStatus;
import com.scheduler.repository.JobRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JobSchedulerService
 */
@ExtendWith(MockitoExtension.class)
class JobSchedulerServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private FlywayMigrationService migrationService;

    @Mock
    private ConcurrencyCapManager capManager;

    @Mock
    private TargetService targetService;

    @Mock
    private SseService sseService;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    private JobSchedulerService schedulerService;
    private JobSchedulerService realService;

    @BeforeEach
    void setUp() {
        TenantContext.clear();

        // Create real instance (self will be set to itself)
        realService = new JobSchedulerService(
            jobRepository, migrationService, capManager,
            targetService, sseService, entityManager, null
        );

        // Set self reference to itself for method delegation
        ReflectionTestUtils.setField(realService, "self", realService);
        ReflectionTestUtils.setField(realService, "tenantMax", 10);

        // Mock entity manager with lenient stubbing
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.executeUpdate()).thenReturn(0);
    }

    // ========== scheduleJobs() Tests ==========

    @Test
    void testScheduleJobs_NoActiveTenants() {
        // Arrange
        when(migrationService.getMigratedSchemas()).thenReturn(Collections.emptySet());

        // Act
        realService.scheduleJobs();

        // Assert - should return early without scheduling
        verify(capManager, never()).tryAcquire(anyString(), anyString());
    }

    @Test
    void testScheduleJobs_WithActiveTenants() {
        // Arrange
        Set<String> schemas = new LinkedHashSet<>(Arrays.asList("tenant_tenant1", "tenant_tenant2"));
        when(migrationService.getMigratedSchemas()).thenReturn(schemas);
        when(jobRepository.countByStatus(JobStatus.PENDING)).thenReturn(5L, 3L);
        when(capManager.getGlobalAvailable()).thenReturn(100);
        when(jobRepository.countByTenantIdAndStatus(anyString(), eq(JobStatus.RUNNING))).thenReturn(2L);
        when(jobRepository.findPendingJobsOrderedByCreation(JobStatus.PENDING)).thenReturn(Collections.emptyList());

        // Act
        realService.scheduleJobs();

        // Assert
        verify(jobRepository, times(2)).countByStatus(JobStatus.PENDING);
    }

    // ========== calculateFairShare() Tests ==========

    @Test
    void testCalculateFairShare_EqualDistribution() {
        // Test via reflection since it's private
        Integer result = ReflectionTestUtils.invokeMethod(realService, "calculateFairShare", 100, 5);

        // 100 / 5 = 20, but tenant max is 10
        assertEquals(10, result);
    }

    @Test
    void testCalculateFairShare_ZeroTenants() {
        Integer result = ReflectionTestUtils.invokeMethod(realService, "calculateFairShare", 100, 0);
        assertEquals(0, result);
    }

    @Test
    void testCalculateFairShare_MoreTenantsThanCapacity() {
        Integer result = ReflectionTestUtils.invokeMethod(realService, "calculateFairShare", 3, 10);

        // 3 / 10 = 0, should give at least 0
        assertEquals(0, result);
    }

    // ========== claimJob() Tests ==========

    @Test
    void testClaimJob_Success() {
        // Arrange
        UUID jobId = UUID.randomUUID();
        Job pendingJob = Job.builder()
            .id(jobId)
            .status(JobStatus.PENDING)
            .tenantId("tenant1")
            .targetId("target1")
            .build();

        TenantContext.setTenantId("tenant1");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(pendingJob));
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        Job result = realService.claimJob(jobId);

        // Assert
        assertNotNull(result);
        assertEquals(JobStatus.RUNNING, result.getStatus());
        assertNotNull(result.getStartedAt());
        verify(sseService).publishJobUpdate(any(Job.class));
    }

    @Test
    void testClaimJob_AlreadyClaimed() {
        // Arrange
        UUID jobId = UUID.randomUUID();
        Job runningJob = Job.builder()
            .id(jobId)
            .status(JobStatus.RUNNING)
            .build();

        TenantContext.setTenantId("tenant1");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(runningJob));

        // Act
        Job result = realService.claimJob(jobId);

        // Assert
        assertNull(result);
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void testClaimJob_JobNotFound() {
        // Arrange
        UUID jobId = UUID.randomUUID();
        TenantContext.setTenantId("tenant1");
        when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

        // Act
        Job result = realService.claimJob(jobId);

        // Assert
        assertNull(result);
        verify(jobRepository, never()).saveAndFlush(any());
    }

    // ========== completeJob() Tests ==========

    @Test
    void testCompleteJob_Success() {
        // Arrange
        Job job = Job.builder()
            .id(UUID.randomUUID())
            .tenantId("tenant1")
            .targetId("target1")
            .status(JobStatus.RUNNING)
            .build();

        TenantContext.setTenantId("tenant1");
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        realService.completeJob(job, true);

        // Assert
        verify(jobRepository).saveAndFlush(argThat(j ->
            j.getStatus() == JobStatus.SUCCEEDED && j.getCompletedAt() != null
        ));
        verify(capManager).release("tenant1", "target1");
        verify(sseService).publishJobUpdate(any(Job.class));
    }

    @Test
    void testCompleteJob_FailureWithRetries() {
        // Arrange
        Job job = Job.builder()
            .id(UUID.randomUUID())
            .tenantId("tenant1")
            .targetId("target1")
            .status(JobStatus.RUNNING)
            .retryCount(0)
            .maxRetries(3)
            .build();

        TenantContext.setTenantId("tenant1");
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        realService.completeJob(job, false);

        // Assert - should retry
        verify(jobRepository).saveAndFlush(argThat(j ->
            j.getStatus() == JobStatus.PENDING && j.getRetryCount() == 1
        ));
        verify(capManager).release("tenant1", "target1");
    }

    @Test
    void testCompleteJob_FailureMaxRetriesExceeded() {
        // Arrange
        Job job = Job.builder()
            .id(UUID.randomUUID())
            .tenantId("tenant1")
            .targetId("target1")
            .status(JobStatus.RUNNING)
            .retryCount(3)
            .maxRetries(3)
            .build();

        TenantContext.setTenantId("tenant1");
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        realService.completeJob(job, false);

        // Assert - should mark as failed
        verify(jobRepository).saveAndFlush(argThat(j ->
            j.getStatus() == JobStatus.FAILED &&
            j.getCompletedAt() != null &&
            j.getErrorMessage() != null
        ));
        verify(capManager).release("tenant1", "target1");
    }

    @Test
    void testCompleteJob_JobNotFound() {
        // Arrange
        Job job = Job.builder()
            .id(UUID.randomUUID())
            .tenantId("tenant1")
            .targetId("target1")
            .build();

        TenantContext.setTenantId("tenant1");
        when(jobRepository.findById(job.getId())).thenReturn(Optional.empty());

        // Act
        realService.completeJob(job, true);

        // Assert
        verify(jobRepository, never()).saveAndFlush(any());
        verify(capManager, never()).release(anyString(), anyString());
    }

    // ========== failJob() Tests ==========

    @Test
    void testFailJob_Success() {
        // Arrange
        UUID jobId = UUID.randomUUID();
        Job job = Job.builder()
            .id(jobId)
            .tenantId("tenant1")
            .targetId("target1")
            .status(JobStatus.RUNNING)
            .build();

        TenantContext.setTenantId("tenant1");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        realService.failJob(jobId, "Test error");

        // Assert
        verify(jobRepository).saveAndFlush(argThat(j ->
            j.getStatus() == JobStatus.FAILED &&
            j.getErrorMessage().equals("Test error") &&
            j.getCompletedAt() != null
        ));
        verify(capManager).release("tenant1", "target1");
        verify(sseService).publishJobUpdate(any(Job.class));
    }

    @Test
    void testFailJob_JobNotFound() {
        // Arrange
        UUID jobId = UUID.randomUUID();
        TenantContext.setTenantId("tenant1");
        when(jobRepository.findById(jobId)).thenReturn(Optional.empty());

        // Act
        realService.failJob(jobId, "Test error");

        // Assert
        verify(jobRepository, never()).saveAndFlush(any());
        verify(capManager, never()).release(anyString(), anyString());
    }

    // ========== countPendingJobsForTenant() Tests ==========

    @Test
    void testCountPendingJobsForTenant() {
        // Arrange
        TenantContext.setTenantId("tenant1");
        when(jobRepository.countByStatus(JobStatus.PENDING)).thenReturn(5L);

        // Act
        long count = realService.countPendingJobsForTenant();

        // Assert
        assertEquals(5L, count);
        verify(jobRepository).countByStatus(JobStatus.PENDING);
    }

    // ========== countRunningJobsForTenant() Tests ==========

    @Test
    void testCountRunningJobsForTenant() {
        // Arrange
        TenantContext.setTenantId("tenant1");
        when(jobRepository.countByTenantIdAndStatus("tenant1", JobStatus.RUNNING)).thenReturn(3L);

        // Act
        long count = realService.countRunningJobsForTenant("tenant1");

        // Assert
        assertEquals(3L, count);
        verify(jobRepository).countByTenantIdAndStatus("tenant1", JobStatus.RUNNING);
    }

    // ========== findPendingJobsForTenant() Tests ==========

    @Test
    void testFindPendingJobsForTenant() {
        // Arrange
        TenantContext.setTenantId("tenant1");
        List<Job> jobs = Arrays.asList(
            Job.builder().id(UUID.randomUUID()).status(JobStatus.PENDING).build(),
            Job.builder().id(UUID.randomUUID()).status(JobStatus.PENDING).build()
        );
        when(jobRepository.findPendingJobsOrderedByCreation(JobStatus.PENDING)).thenReturn(jobs);

        // Act
        List<Job> result = realService.findPendingJobsForTenant();

        // Assert
        assertEquals(2, result.size());
        verify(jobRepository).findPendingJobsOrderedByCreation(JobStatus.PENDING);
    }

    // ========== handleFailure() Tests ==========

    @Test
    void testHandleFailure_WithinRetryLimit() {
        // Arrange
        Job job = Job.builder()
            .id(UUID.randomUUID())
            .retryCount(1)
            .maxRetries(3)
            .status(JobStatus.RUNNING)
            .startedAt(LocalDateTime.now())
            .build();

        // Act via completeJob
        TenantContext.setTenantId("tenant1");
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        realService.completeJob(job, false);

        // Assert
        verify(jobRepository).saveAndFlush(argThat(j ->
            j.getStatus() == JobStatus.PENDING &&
            j.getRetryCount() == 2 &&
            j.getStartedAt() == null
        ));
    }

    @Test
    void testHandleFailure_MaxRetriesReached() {
        // Arrange
        Job job = Job.builder()
            .id(UUID.randomUUID())
            .retryCount(3)
            .maxRetries(3)
            .status(JobStatus.RUNNING)
            .build();

        // Act via completeJob
        TenantContext.setTenantId("tenant1");
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        realService.completeJob(job, false);

        // Assert
        verify(jobRepository).saveAndFlush(argThat(j ->
            j.getStatus() == JobStatus.FAILED &&
            j.getCompletedAt() != null &&
            "Max retries exceeded".equals(j.getErrorMessage())
        ));
    }

    // ========== scheduleJobsForTenant() Tests ==========

    @Test
    void testScheduleJobsForTenant_NoCapacity() {
        // Arrange
        when(jobRepository.countByTenantIdAndStatus("tenant1", JobStatus.RUNNING)).thenReturn(10L);
        ReflectionTestUtils.setField(realService, "tenantMax", 10);

        // Act
        ReflectionTestUtils.invokeMethod(realService, "scheduleJobsForTenant", "tenant1", 5);

        // Assert - should not schedule any jobs
        verify(jobRepository, never()).findPendingJobsOrderedByCreation(any());
    }

    @Test
    void testScheduleJobsForTenant_WithAvailableCapacity() {
        // Arrange
        Job job1 = Job.builder()
            .id(UUID.randomUUID())
            .tenantId("tenant1")
            .targetId("target1")
            .status(JobStatus.PENDING)
            .build();

        Job job2 = Job.builder()
            .id(UUID.randomUUID())
            .tenantId("tenant1")
            .targetId("target2")
            .status(JobStatus.PENDING)
            .build();

        when(jobRepository.countByTenantIdAndStatus("tenant1", JobStatus.RUNNING)).thenReturn(2L);
        when(jobRepository.findPendingJobsOrderedByCreation(JobStatus.PENDING))
            .thenReturn(Arrays.asList(job1, job2));
        when(capManager.tryAcquire(eq("tenant1"), eq("target1"))).thenReturn(true);
        when(capManager.tryAcquire(eq("tenant1"), eq("target2"))).thenReturn(true);

        // Act
        ReflectionTestUtils.invokeMethod(realService, "scheduleJobsForTenant", "tenant1", 5);

        // Assert
        verify(capManager).tryAcquire("tenant1", "target1");
        verify(capManager).tryAcquire("tenant1", "target2");
    }

    @Test
    void testScheduleJobsForTenant_CapacityExhausted() {
        // Arrange
        Job job = Job.builder()
            .id(UUID.randomUUID())
            .tenantId("tenant1")
            .targetId("target1")
            .status(JobStatus.PENDING)
            .build();

        when(jobRepository.countByTenantIdAndStatus("tenant1", JobStatus.RUNNING)).thenReturn(2L);
        when(jobRepository.findPendingJobsOrderedByCreation(JobStatus.PENDING))
            .thenReturn(Collections.singletonList(job));
        when(capManager.tryAcquire(anyString(), anyString())).thenReturn(false);

        // Act
        ReflectionTestUtils.invokeMethod(realService, "scheduleJobsForTenant", "tenant1", 5);

        // Assert - tried to acquire but failed
        verify(capManager).tryAcquire("tenant1", "target1");
    }

    // ========== getActiveTenantsWithPendingJobs() Tests ==========

    @Test
    void testGetActiveTenantsWithPendingJobs_MultipleTenants() {
        // Arrange
        Set<String> schemas = new LinkedHashSet<>(Arrays.asList("tenant_tenant1", "tenant_tenant2", "tenant_tenant3"));
        when(migrationService.getMigratedSchemas()).thenReturn(schemas);
        when(jobRepository.countByStatus(JobStatus.PENDING))
            .thenReturn(5L)  // tenant1 has jobs
            .thenReturn(0L)  // tenant2 has no jobs
            .thenReturn(3L); // tenant3 has jobs

        // Act
        Set<String> result = ReflectionTestUtils.invokeMethod(
            realService, "getActiveTenantsWithPendingJobs"
        );

        // Assert
        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.contains("tenant1"));
        assertTrue(result.contains("tenant3"));
        assertFalse(result.contains("tenant2"));
    }

    @Test
    void testGetActiveTenantsWithPendingJobs_NoTenants() {
        // Arrange
        when(migrationService.getMigratedSchemas()).thenReturn(Collections.emptySet());

        // Act
        Set<String> result = ReflectionTestUtils.invokeMethod(
            realService, "getActiveTenantsWithPendingJobs"
        );

        // Assert
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetActiveTenantsWithPendingJobs_ErrorHandling() {
        // Arrange
        Set<String> schemas = new LinkedHashSet<>(Arrays.asList("tenant_tenant1", "tenant_tenant2"));
        when(migrationService.getMigratedSchemas()).thenReturn(schemas);
        when(jobRepository.countByStatus(JobStatus.PENDING))
            .thenReturn(5L)  // tenant1 succeeds
            .thenThrow(new RuntimeException("DB error")); // tenant2 fails

        // Act
        Set<String> result = ReflectionTestUtils.invokeMethod(
            realService, "getActiveTenantsWithPendingJobs"
        );

        // Assert - should continue despite error
        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.contains("tenant1"));
    }
}
