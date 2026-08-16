package com.scheduler.service;

import com.scheduler.config.TenantContext;
import com.scheduler.model.Job;
import com.scheduler.model.JobStatus;
import com.scheduler.repository.JobRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class JobSchedulerService {

    private final JobRepository jobRepository;
    private final FlywayMigrationService migrationService;
    private final ConcurrencyCapManager capManager;
    private final TargetService targetService;
    private final SseService sseService;
    private final EntityManager entityManager;

    // Lazy self-reference for calling transactional methods through proxy
    private final JobSchedulerService self;

    @Value("${scheduler.concurrency.tenant-max}")
    private int tenantMax;

    public JobSchedulerService(
            JobRepository jobRepository,
            FlywayMigrationService migrationService,
            ConcurrencyCapManager capManager,
            TargetService targetService,
            SseService sseService,
            EntityManager entityManager,
            @Lazy JobSchedulerService self) {
        this.jobRepository = jobRepository;
        this.migrationService = migrationService;
        this.capManager = capManager;
        this.targetService = targetService;
        this.sseService = sseService;
        this.entityManager = entityManager;
        this.self = self;
    }

    private void setTenantSchema(String tenantId) {
        String schema = "tenant_" + tenantId;
        entityManager.createNativeQuery("SET search_path TO \"" + schema + "\"")
                .executeUpdate();
        log.debug("Set search_path to: {}", schema);
    }

    /**
     * Round-robin fair scheduler that prevents tenant starvation.
     *
     * Algorithm:
     * 1. Identify all tenants with pending jobs (active tenants)
     * 2. Calculate fair share: divide available global capacity equally among active tenants
     * 3. For each tenant, schedule up to their fair share (respecting per-tenant cap)
     * 4. Jobs are scheduled oldest-first within each tenant
     *
     * This ensures:
     * - No tenant can monopolize capacity
     * - Each active tenant gets equal opportunity
     * - Low-volume tenants don't starve when competing with high-volume tenants
     */
    @Scheduled(fixedDelayString = "${scheduler.worker.poll-interval-ms}")
    public void scheduleJobs() {
        // Get all tenants with pending jobs
        Set<String> activeTenants = getActiveTenantsWithPendingJobs();

        if (activeTenants.isEmpty()) {
            return;
        }

        // Calculate fair share per tenant
        int globalAvailable = capManager.getGlobalAvailable();
        int fairShare = calculateFairShare(globalAvailable, activeTenants.size());

        log.debug("Round-robin scheduling: {} active tenants, {} available slots, {} fair share per tenant",
                 activeTenants.size(), globalAvailable, fairShare);

        // Schedule jobs for each tenant in round-robin fashion
        for (String tenantId : activeTenants) {
            try {
                scheduleJobsForTenant(tenantId, fairShare);
            } catch (Exception e) {
                log.error("Error scheduling jobs for tenant: {}", tenantId, e);
            }
        }
    }

    /**
     * Get list of tenants that have pending jobs.
     * These are the "active" tenants competing for capacity.
     */
    private Set<String> getActiveTenantsWithPendingJobs() {
        Set<String> activeTenants = new java.util.LinkedHashSet<>();
        Set<String> tenantSchemas = migrationService.getMigratedSchemas();

        for (String schemaName : tenantSchemas) {
            String tenantId = schemaName.replace("tenant_", "");
            TenantContext.setTenantId(tenantId);

            try {
                long pendingCount = self.countPendingJobsForTenant();
                if (pendingCount > 0) {
                    activeTenants.add(tenantId);
                }
            } catch (Exception e) {
                log.warn("Error checking pending jobs for tenant: {}", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }

        return activeTenants;
    }

    @Transactional(readOnly = true)
    protected long countPendingJobsForTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            setTenantSchema(tenantId);
        }
        return jobRepository.countByStatus(JobStatus.PENDING);
    }

    /**
     * Calculate fair share of capacity per tenant.
     *
     * @param globalAvailable Total available capacity
     * @param tenantCount Number of active tenants
     * @return Fair share per tenant (respecting per-tenant cap)
     */
    private int calculateFairShare(int globalAvailable, int tenantCount) {
        if (tenantCount == 0) {
            return 0;
        }

        // Divide available capacity equally
        int perTenant = globalAvailable / tenantCount;

        // Respect per-tenant cap (don't allocate more than tenant max)
        return Math.min(perTenant, tenantMax);
    }

    /**
     * Schedule jobs for a specific tenant up to their quota.
     * Jobs are scheduled oldest-first.
     *
     * @param tenantId Tenant to schedule jobs for
     * @param quota Maximum number of jobs to schedule for this tenant
     */
    private void scheduleJobsForTenant(String tenantId, int quota) {
        TenantContext.setTenantId(tenantId);
        log.debug("scheduleJobsForTenant: Starting for tenant={}, quota={}, globalAvail={}",
                 tenantId, quota, capManager.getGlobalAvailable());

        try {
            // How many jobs is this tenant currently running?
            long currentRunning = self.countRunningJobsForTenant(tenantId);
            log.debug("scheduleJobsForTenant: Tenant {} has {} jobs running", tenantId, currentRunning);

            // How many more can they schedule?
            int availableSlots = (int) Math.min(
                    quota,
                    tenantMax - currentRunning
            );

            if (availableSlots <= 0) {
                log.debug("Tenant {} at capacity: {} running, quota {}",
                         tenantId, currentRunning, quota);
                return;
            }

            // Get oldest pending jobs for this tenant (FIFO within tenant)
            List<Job> pendingJobs = self.findPendingJobsForTenant();
            log.debug("scheduleJobsForTenant: Found {} pending jobs for tenant {}", pendingJobs.size(), tenantId);

            int scheduled = 0;
            for (Job job : pendingJobs) {
                // Try to acquire concurrency permits
                if (capManager.tryAcquire(job.getTenantId(), job.getTargetId())) {
                    log.info("scheduleJobsForTenant: Submitting job {} to executor", job.getId());
                    // Use self to invoke through Spring proxy for @Async and @Transactional
                    self.executeJobAsync(job.getId(), job.getTenantId());
                    scheduled++;

                    // Important: Increment currentRunning for this scheduling cycle
                    // This prevents scheduling more than tenantMax jobs before they transition to RUNNING
                    currentRunning++;

                    // Recalculate available slots for next iteration
                    availableSlots = (int) Math.min(
                            quota - scheduled,
                            tenantMax - currentRunning
                    );

                    if (availableSlots <= 0) {
                        log.debug("Tenant {} reached capacity in this scheduling cycle", tenantId);
                        break;
                    }
                } else {
                    log.debug("scheduleJobsForTenant: Failed to acquire permits for job {}", job.getId());
                }
            }

            if (scheduled > 0) {
                log.info("Scheduled {} jobs for tenant {} (quota: {}, running: {})",
                         scheduled, tenantId, quota, currentRunning - scheduled);
            } else {
                log.debug("scheduleJobsForTenant: No jobs scheduled for tenant {}", tenantId);
            }

        } finally {
            TenantContext.clear();
        }
    }

    @Transactional(readOnly = true)
    protected long countRunningJobsForTenant(String tenantId) {
        setTenantSchema(tenantId);
        return jobRepository.countByTenantIdAndStatus(tenantId, JobStatus.RUNNING);
    }

    @Transactional(readOnly = true)
    protected List<Job> findPendingJobsForTenant() {
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            setTenantSchema(tenantId);
        }
        return jobRepository.findPendingJobsOrderedByCreation(JobStatus.PENDING);
    }

    @Async("jobExecutor")
    public void executeJobAsync(java.util.UUID jobId, String tenantId) {
        // IMPORTANT: Set tenant context BEFORE any database operations
        TenantContext.setTenantId(tenantId);
        log.info("executeJobAsync: Starting job {} for tenant {}", jobId, tenantId);

        try {
            // Claim the job (transactional) - call through proxy
            Job job = self.claimJob(jobId);

            if (job == null) {
                // Job already claimed by another worker
                capManager.release(tenantId, getTargetIdFromJob(jobId));
                return;
            }

            log.info("executeJobAsync: Claimed job {}", jobId);

            // Execute the target (non-transactional, simulated work)
            boolean success = targetService.execute(job.getTargetId(), job.getPayload());

            // Update job status (transactional) - call through proxy
            self.completeJob(job, success);

        } catch (OptimisticLockException e) {
            log.debug("Job {} already claimed by another worker", jobId);
            capManager.release(tenantId, getTargetIdFromJob(jobId));
        } catch (Exception e) {
            log.error("Error executing job: {}", jobId, e);
            try {
                self.failJob(jobId, e.getMessage());
            } catch (Exception ex) {
                log.error("Error marking job as failed: {}", jobId, ex);
            }
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected Job claimJob(java.util.UUID jobId) {
        // Set schema AFTER transaction starts but BEFORE any queries
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            setTenantSchema(tenantId);
        }

        Job job = jobRepository.findById(jobId).orElse(null);

        if (job == null || job.getStatus() != JobStatus.PENDING) {
            return null;
        }

        job.setStatus(JobStatus.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        job = jobRepository.saveAndFlush(job);

        log.info("Claimed job {} for execution", jobId);

        // Publish SSE update
        sseService.publishJobUpdate(job);

        return job;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void completeJob(Job job, boolean success) {
        // Set schema AFTER transaction starts but BEFORE any queries
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            setTenantSchema(tenantId);
        }

        Job updatedJob = jobRepository.findById(job.getId()).orElse(null);
        if (updatedJob == null) {
            return;
        }

        if (success) {
            updatedJob.setStatus(JobStatus.SUCCEEDED);
            updatedJob.setCompletedAt(LocalDateTime.now());
            log.info("Job {} completed successfully", job.getId());
        } else {
            handleFailure(updatedJob);
        }

        jobRepository.saveAndFlush(updatedJob);

        // Release permits
        capManager.release(updatedJob.getTenantId(), updatedJob.getTargetId());

        // Publish SSE update
        sseService.publishJobUpdate(updatedJob);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failJob(java.util.UUID jobId, String errorMessage) {
        // Set schema AFTER transaction starts but BEFORE any queries
        String tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            setTenantSchema(tenantId);
        }

        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }

        job.setStatus(JobStatus.FAILED);
        job.setCompletedAt(LocalDateTime.now());
        job.setErrorMessage(errorMessage);
        jobRepository.saveAndFlush(job);

        capManager.release(job.getTenantId(), job.getTargetId());

        // Publish SSE update
        sseService.publishJobUpdate(job);
    }

    private void handleFailure(Job job) {
        if (job.getRetryCount() < job.getMaxRetries()) {
            job.setRetryCount(job.getRetryCount() + 1);
            job.setStatus(JobStatus.PENDING);
            job.setStartedAt(null);
            log.info("Job {} failed, retry {}/{}", job.getId(), job.getRetryCount(), job.getMaxRetries());
        } else {
            job.setStatus(JobStatus.FAILED);
            job.setCompletedAt(LocalDateTime.now());
            job.setErrorMessage("Max retries exceeded");
            log.info("Job {} failed after {} retries", job.getId(), job.getMaxRetries());
        }
    }

    private String getTargetIdFromJob(java.util.UUID jobId) {
        try {
            Job job = jobRepository.findById(jobId).orElse(null);
            return job != null ? job.getTargetId() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
