package com.scheduler.controller;

import com.scheduler.dto.JobRequest;
import com.scheduler.dto.JobResponse;
import com.scheduler.service.JobService;
import com.scheduler.service.SseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final SseService sseService;

    @PostMapping
    public ResponseEntity<JobResponse> createJob(
            @Valid @RequestBody JobRequest request,
            @RequestHeader("X-Tenant-Id") String tenantId) {

        log.info("Received job creation request for tenant: {}, idempotency key: {}",
                tenantId, request.getIdempotencyKey());

        // Reject cross-tenant writes: if the body specifies a tenantId, it must
        // match the authoritative X-Tenant-Id header.
        if (request.getTenantId() != null && !request.getTenantId().equals(tenantId)) {
            log.warn("Tenant ID mismatch: header={}, body={}", tenantId, request.getTenantId());
            return ResponseEntity.badRequest().build();
        }

        // Ensure the tenant is taken from the trusted header.
        request.setTenantId(tenantId);

        JobResponse response = jobService.createJob(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<JobResponse>> getJobs(
            @RequestHeader("X-Tenant-Id") String tenantId) {

        log.info("Fetching jobs for tenant: {}", tenantId);
        List<JobResponse> jobs = jobService.getJobsByTenant(tenantId);
        return ResponseEntity.ok(jobs);
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobResponse> getJob(
            @PathVariable UUID jobId,
            @RequestHeader("X-Tenant-Id") String tenantId) {

        log.info("Fetching job {} for tenant: {}", jobId, tenantId);
        return jobService.getJob(tenantId, jobId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJobs(@RequestHeader(value = "X-Tenant-Id") String tenantId) {
        log.info("Opening SSE stream for tenant: {}", tenantId);
        return sseService.addEmitter(tenantId);
    }
}
