package com.scheduler.service;

import com.scheduler.config.TenantContext;
import com.scheduler.dto.JobRequest;
import com.scheduler.dto.JobResponse;
import com.scheduler.model.Job;
import com.scheduler.model.JobStatus;
import com.scheduler.repository.JobRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final FlywayMigrationService migrationService;
    private final SseService sseService;
    private final EntityManager entityManager;

    private void setTenantSchema(String tenantId) {
        String schema = "tenant_" + tenantId;
        entityManager.createNativeQuery("SET search_path TO \"" + schema + "\"")
                .executeUpdate();
        log.debug("Set search_path to: {}", schema);
    }

    @Transactional
    public JobResponse createJob(JobRequest request) {
        String tenantId = request.getTenantId();

        // Ensure tenant schema exists
        migrationService.ensureTenantSchema(tenantId);

        // Set tenant context for this transaction
        TenantContext.setTenantId(tenantId);

        // Set search_path after transaction starts
        setTenantSchema(tenantId);

        try {
            // Check for existing job with same idempotency key
            Optional<Job> existingJob = jobRepository.findByIdempotencyKey(request.getIdempotencyKey());

            if (existingJob.isPresent()) {
                log.info("Job with idempotency key {} already exists, returning existing job",
                        request.getIdempotencyKey());
                return toResponse(existingJob.get());
            }

            // Create new job
            Job job = Job.builder()
                    .tenantId(request.getTenantId())
                    .targetId(request.getTargetId())
                    .payload(request.getPayload())
                    .idempotencyKey(request.getIdempotencyKey())
                    .status(JobStatus.PENDING)
                    .retryCount(0)
                    .maxRetries(3)
                    .build();

            try {
                Job savedJob = jobRepository.save(job);
                log.info("Created new job {} for tenant {} with idempotency key {}",
                        savedJob.getId(), tenantId, request.getIdempotencyKey());

                // Publish SSE update for new job
                sseService.publishJobUpdate(savedJob);

                return toResponse(savedJob);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Race condition: another thread created job with same key between check and save
                log.warn("Concurrent duplicate detected for idempotency key {}, fetching existing job",
                        request.getIdempotencyKey());
                existingJob = jobRepository.findByIdempotencyKey(request.getIdempotencyKey());
                if (existingJob.isPresent()) {
                    return toResponse(existingJob.get());
                }
                throw e; // Re-throw if we still can't find it
            }

        } finally {
            TenantContext.clear();
        }
    }

    @Transactional(readOnly = true)
    public List<JobResponse> getJobsByTenant(String tenantId) {
        migrationService.ensureTenantSchema(tenantId);
        TenantContext.setTenantId(tenantId);
        setTenantSchema(tenantId);
        try {
            List<Job> jobs = jobRepository.findAll();
            return jobs.stream()
                    .map(this::toResponse)
                    .collect(Collectors.toList());
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional(readOnly = true)
    public Optional<JobResponse> getJob(String tenantId, UUID jobId) {
        migrationService.ensureTenantSchema(tenantId);
        TenantContext.setTenantId(tenantId);
        setTenantSchema(tenantId);
        try {
            return jobRepository.findById(jobId)
                    .map(this::toResponse);
        } finally {
            TenantContext.clear();
        }
    }

    private JobResponse toResponse(Job job) {
        return JobResponse.builder()
                .id(job.getId())
                .tenantId(job.getTenantId())
                .targetId(job.getTargetId())
                .payload(job.getPayload())
                .idempotencyKey(job.getIdempotencyKey())
                .status(job.getStatus())
                .retryCount(job.getRetryCount())
                .maxRetries(job.getMaxRetries())
                .createdAt(job.getCreatedAt())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .errorMessage(job.getErrorMessage())
                .build();
    }
}
