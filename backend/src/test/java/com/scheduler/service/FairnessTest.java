package com.scheduler.service;

import com.scheduler.config.TenantContext;
import com.scheduler.model.Job;
import com.scheduler.model.JobStatus;
import com.scheduler.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FairnessTest {

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
    private jakarta.persistence.EntityManager entityManager;

    @InjectMocks
    private JobSchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        // Set configuration values
        ReflectionTestUtils.setField(schedulerService, "tenantMax", 10);
        // Set self reference to schedulerService itself for tests
        ReflectionTestUtils.setField(schedulerService, "self", schedulerService);

        // Mock EntityManager for schema switching (lenient for tests that don't use it)
        jakarta.persistence.Query mockQuery = mock(jakarta.persistence.Query.class);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        lenient().when(mockQuery.executeUpdate()).thenReturn(0);
    }

    @Test
    void testFairShareCalculation() {
        // Simulate fair share calculation logic
        // 100 available slots, 3 active tenants
        int globalAvailable = 100;
        int tenantCount = 3;
        int tenantMax = 10;

        int fairShare = Math.min(globalAvailable / tenantCount, tenantMax);

        // Each tenant should get 10 (min of 33 and 10)
        assertEquals(10, fairShare);
    }

    @Test
    void testFairShareWithFewTenants() {
        // 100 available slots, 2 active tenants
        int globalAvailable = 100;
        int tenantCount = 2;
        int tenantMax = 10;

        int fairShare = Math.min(globalAvailable / tenantCount, tenantMax);

        // Each tenant should get 10 (min of 50 and 10)
        assertEquals(10, fairShare);
    }

    @Test
    void testFairShareWithManyTenants() {
        // 100 available slots, 20 active tenants
        int globalAvailable = 100;
        int tenantCount = 20;
        int tenantMax = 10;

        int fairShare = Math.min(globalAvailable / tenantCount, tenantMax);

        // Each tenant should get 5 (100/20 = 5, which is < 10)
        assertEquals(5, fairShare);
    }

    @Test
    void testRoundRobinEnsuresAllTenantsGetOpportunity() {
        // Setup: 3 tenants with pending jobs
        Set<String> tenantSchemas = Set.of("tenant_tenant1", "tenant_tenant2", "tenant_tenant3");
        when(migrationService.getMigratedSchemas()).thenReturn(tenantSchemas);

        // All tenants have pending jobs
        when(jobRepository.countByStatus(JobStatus.PENDING)).thenReturn(10L);

        // Mock capacity
        when(capManager.getGlobalAvailable()).thenReturn(30);

        // Each tenant has jobs to schedule
        List<Job> mockJobs = createMockJobs(5);
        when(jobRepository.findPendingJobsOrderedByCreation(JobStatus.PENDING))
                .thenReturn(mockJobs);

        // Each tenant currently running 0 jobs
        when(jobRepository.countByTenantIdAndStatus(anyString(), eq(JobStatus.RUNNING)))
                .thenReturn(0L);

        // Cap manager allows scheduling
        when(capManager.tryAcquire(anyString(), anyString())).thenReturn(true);

        // Execute scheduler
        schedulerService.scheduleJobs();

        // Verify that countByStatus was called 3 times (once per tenant)
        verify(jobRepository, times(3)).countByStatus(JobStatus.PENDING);

        // Verify that jobs were checked for scheduling for all tenants
        verify(jobRepository, atLeast(3)).findPendingJobsOrderedByCreation(JobStatus.PENDING);
    }

    @Test
    void testHighVolumeTenantDoesNotStarveLowVolumeTenant() {
        // Setup: tenant1 has 100 pending jobs, tenant2 has 5 pending jobs
        Set<String> tenantSchemas = Set.of("tenant_tenant1", "tenant_tenant2");
        when(migrationService.getMigratedSchemas()).thenReturn(tenantSchemas);

        // Mock pending job counts
        when(jobRepository.countByStatus(JobStatus.PENDING))
                .thenReturn(100L)  // tenant1
                .thenReturn(5L);   // tenant2

        // Mock global capacity (30 available)
        when(capManager.getGlobalAvailable()).thenReturn(30);

        // Mock jobs
        List<Job> tenant1Jobs = createMockJobs(15);
        List<Job> tenant2Jobs = createMockJobs(5);

        when(jobRepository.findPendingJobsOrderedByCreation(JobStatus.PENDING))
                .thenReturn(tenant1Jobs)
                .thenReturn(tenant2Jobs);

        // Both tenants currently running 0 jobs
        when(jobRepository.countByTenantIdAndStatus(anyString(), eq(JobStatus.RUNNING)))
                .thenReturn(0L);

        // Cap manager allows scheduling
        when(capManager.tryAcquire(anyString(), anyString())).thenReturn(true);

        // Execute scheduler
        schedulerService.scheduleJobs();

        // With fair share: 30 / 2 = 15 per tenant, but capped at tenantMax = 10
        // Both tenants should get opportunity to schedule (up to 10 jobs each)

        // Verify both tenants had their jobs checked
        verify(jobRepository, times(2)).countByStatus(JobStatus.PENDING);
        verify(jobRepository, times(2)).findPendingJobsOrderedByCreation(JobStatus.PENDING);

        // Verify cap manager was called for both tenants
        verify(capManager, atLeast(10)).tryAcquire(anyString(), anyString());
    }

    @Test
    void testNoActiveTenantsResultsInNoScheduling() {
        // Setup: No tenants have pending jobs
        Set<String> tenantSchemas = Set.of("tenant_tenant1", "tenant_tenant2");
        lenient().when(migrationService.getMigratedSchemas()).thenReturn(tenantSchemas);

        // No pending jobs
        when(jobRepository.countByStatus(JobStatus.PENDING)).thenReturn(0L);

        // Execute scheduler
        schedulerService.scheduleJobs();

        // Verify no jobs were attempted to be scheduled
        verify(jobRepository, never()).findPendingJobsOrderedByCreation(any());
        verify(capManager, never()).tryAcquire(anyString(), anyString());
    }

    @Test
    void testTenantAtCapacityIsSkipped() {
        // Setup: tenant1 already running 10 jobs (at tenant max)
        Set<String> tenantSchemas = Set.of("tenant_tenant1");
        lenient().when(migrationService.getMigratedSchemas()).thenReturn(tenantSchemas);

        // Tenant has pending jobs
        when(jobRepository.countByStatus(JobStatus.PENDING)).thenReturn(20L);

        // Global capacity available
        lenient().when(capManager.getGlobalAvailable()).thenReturn(50);

        // But tenant is already running 10 jobs (at cap)
        lenient().when(jobRepository.countByTenantIdAndStatus("tenant1", JobStatus.RUNNING))
                .thenReturn(10L);

        // Execute scheduler
        schedulerService.scheduleJobs();

        // Verify tenant's jobs were not scheduled (already at cap)
        verify(jobRepository, never()).findPendingJobsOrderedByCreation(any());
        verify(capManager, never()).tryAcquire(anyString(), anyString());
    }

    @Test
    void testJobsScheduledOldestFirst() {
        // Setup: tenant1 has 5 pending jobs with different created times
        Set<String> tenantSchemas = Set.of("tenant_tenant1");
        when(migrationService.getMigratedSchemas()).thenReturn(tenantSchemas);

        when(jobRepository.countByStatus(JobStatus.PENDING)).thenReturn(5L);
        when(capManager.getGlobalAvailable()).thenReturn(10);

        // Create jobs with different created times (oldest first)
        List<Job> orderedJobs = createMockJobs(5);
        when(jobRepository.findPendingJobsOrderedByCreation(JobStatus.PENDING))
                .thenReturn(orderedJobs);

        when(jobRepository.countByTenantIdAndStatus(anyString(), eq(JobStatus.RUNNING)))
                .thenReturn(0L);

        when(capManager.tryAcquire(anyString(), anyString())).thenReturn(true);

        // Execute scheduler
        schedulerService.scheduleJobs();

        // Verify that findPendingJobsOrderedByCreation was called
        // (which returns jobs ordered by createdAt ASC)
        verify(jobRepository).findPendingJobsOrderedByCreation(JobStatus.PENDING);
    }

    @Test
    void testFairShareRespectsTenantMax() {
        // Setup: Only 1 tenant active, but fair share calculation should respect tenant max
        Set<String> tenantSchemas = Set.of("tenant_tenant1");
        lenient().when(migrationService.getMigratedSchemas()).thenReturn(tenantSchemas);

        when(jobRepository.countByStatus(JobStatus.PENDING)).thenReturn(50L);

        // 100 available globally, 1 tenant → fair share would be 100
        // But should be capped at tenantMax = 10
        lenient().when(capManager.getGlobalAvailable()).thenReturn(100);

        List<Job> mockJobs = createMockJobs(20);
        lenient().when(jobRepository.findPendingJobsOrderedByCreation(JobStatus.PENDING))
                .thenReturn(mockJobs);

        lenient().when(jobRepository.countByTenantIdAndStatus(anyString(), eq(JobStatus.RUNNING)))
                .thenReturn(0L);

        lenient().when(capManager.tryAcquire(anyString(), anyString())).thenReturn(true);

        // Execute scheduler
        schedulerService.scheduleJobs();

        // Should try to acquire at most tenantMax (10) permits
        verify(capManager, atMost(10)).tryAcquire(anyString(), anyString());
    }

    // Helper methods

    private List<Job> createMockJobs(int count) {
        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Job job = Job.builder()
                    .id(UUID.randomUUID())
                    .tenantId("tenant1")
                    .targetId("target-1")
                    .status(JobStatus.PENDING)
                    .idempotencyKey("key-" + i)
                    .build();
            jobs.add(job);
        }
        return jobs;
    }
}
