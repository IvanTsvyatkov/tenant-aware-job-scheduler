package com.scheduler.controller;

import com.scheduler.dto.JobRequest;
import com.scheduler.dto.JobResponse;
import com.scheduler.model.JobStatus;
import com.scheduler.service.JobService;
import com.scheduler.service.SseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(JobController.class)
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JobService jobService;

    @MockBean
    private SseService sseService;

    @Test
    void testCreateJob_Success() throws Exception {
        String tenantId = "tenant1";

        JobResponse response = JobResponse.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .targetId("target-1")
                .payload("test payload")
                .idempotencyKey("key-123")
                .status(JobStatus.PENDING)
                .retryCount(0)
                .maxRetries(3)
                .createdAt(LocalDateTime.now())
                .build();

        when(jobService.createJob(any(JobRequest.class))).thenReturn(response);

        mockMvc.perform(post("/jobs")
                        .header("X-Tenant-Id", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "tenantId": "tenant1",
                                    "targetId": "target-1",
                                    "payload": "test payload",
                                    "idempotencyKey": "key-123"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(tenantId))
                .andExpect(jsonPath("$.targetId").value("target-1"))
                .andExpect(jsonPath("$.idempotencyKey").value("key-123"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(jobService, times(1)).createJob(any(JobRequest.class));
    }

    @Test
    void testCreateJob_MissingTenantHeader() throws Exception {
        mockMvc.perform(post("/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "tenantId": "tenant1",
                                    "targetId": "target-1",
                                    "payload": "test",
                                    "idempotencyKey": "key-123"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(jobService, never()).createJob(any());
    }

    @Test
    void testCreateJob_InvalidRequest() throws Exception {
        // Missing required fields
        mockMvc.perform(post("/jobs")
                        .header("X-Tenant-Id", "tenant1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "payload": "test"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(jobService, never()).createJob(any());
    }

    @Test
    void testGetJobs_Success() throws Exception {
        String tenantId = "tenant1";
        List<JobResponse> jobs = Arrays.asList(
                createJobResponse(UUID.randomUUID(), tenantId, "target-1", JobStatus.PENDING),
                createJobResponse(UUID.randomUUID(), tenantId, "target-2", JobStatus.RUNNING),
                createJobResponse(UUID.randomUUID(), tenantId, "target-3", JobStatus.SUCCEEDED)
        );

        when(jobService.getJobsByTenant(tenantId)).thenReturn(jobs);

        mockMvc.perform(get("/jobs")
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].tenantId").value(tenantId))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].status").value("RUNNING"))
                .andExpect(jsonPath("$[2].status").value("SUCCEEDED"));

        verify(jobService, times(1)).getJobsByTenant(tenantId);
    }

    @Test
    void testGetJobs_EmptyList() throws Exception {
        String tenantId = "tenant1";
        when(jobService.getJobsByTenant(tenantId)).thenReturn(List.of());

        mockMvc.perform(get("/jobs")
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(jobService, times(1)).getJobsByTenant(tenantId);
    }

    @Test
    void testGetJobs_MissingTenantHeader() throws Exception {
        mockMvc.perform(get("/jobs"))
                .andExpect(status().isBadRequest());

        verify(jobService, never()).getJobsByTenant(any());
    }

    @Test
    void testGetJob_Found() throws Exception {
        String tenantId = "tenant1";
        UUID jobId = UUID.randomUUID();
        JobResponse job = createJobResponse(jobId, tenantId, "target-1", JobStatus.SUCCEEDED);

        when(jobService.getJob(tenantId, jobId)).thenReturn(Optional.of(job));

        mockMvc.perform(get("/jobs/{jobId}", jobId)
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId.toString()))
                .andExpect(jsonPath("$.tenantId").value(tenantId))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        verify(jobService, times(1)).getJob(tenantId, jobId);
    }

    @Test
    void testGetJob_NotFound() throws Exception {
        String tenantId = "tenant1";
        UUID jobId = UUID.randomUUID();

        when(jobService.getJob(tenantId, jobId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/jobs/{jobId}", jobId)
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isNotFound());

        verify(jobService, times(1)).getJob(tenantId, jobId);
    }

    @Test
    void testGetJob_MissingTenantHeader() throws Exception {
        UUID jobId = UUID.randomUUID();

        mockMvc.perform(get("/jobs/{jobId}", jobId))
                .andExpect(status().isBadRequest());

        verify(jobService, never()).getJob(any(), any());
    }

    @Test
    void testStreamJobs_Success() throws Exception {
        String tenantId = "tenant1";
        SseEmitter emitter = new SseEmitter();

        when(sseService.addEmitter(tenantId)).thenReturn(emitter);

        mockMvc.perform(get("/jobs/stream")
                        .header("X-Tenant-Id", tenantId))
                .andExpect(status().isOk());

        verify(sseService, times(1)).addEmitter(tenantId);
    }

    @Test
    void testCreateJob_TenantIdMismatchRejected() throws Exception {
        String headerTenantId = "tenant1";
        String bodyTenantId = "tenant2"; // Different tenant in body

        mockMvc.perform(post("/jobs")
                        .header("X-Tenant-Id", headerTenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {
                                    "tenantId": "%s",
                                    "targetId": "target-1",
                                    "payload": "test",
                                    "idempotencyKey": "key-123"
                                }
                                """, bodyTenantId)))
                .andExpect(status().isBadRequest());

        // Job service should never be called when tenants mismatch
        verify(jobService, never()).createJob(any(JobRequest.class));
    }

    @Test
    void testCreateJob_TenantIdMatchesHeader() throws Exception {
        String tenantId = "tenant1";

        JobResponse response = JobResponse.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .targetId("target-1")
                .payload("test")
                .idempotencyKey("key-123")
                .status(JobStatus.PENDING)
                .retryCount(0)
                .maxRetries(3)
                .createdAt(LocalDateTime.now())
                .build();

        when(jobService.createJob(any(JobRequest.class))).thenReturn(response);

        mockMvc.perform(post("/jobs")
                        .header("X-Tenant-Id", tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("""
                                {
                                    "tenantId": "%s",
                                    "targetId": "target-1",
                                    "payload": "test",
                                    "idempotencyKey": "key-123"
                                }
                                """, tenantId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value(tenantId));

        verify(jobService, times(1)).createJob(any(JobRequest.class));
    }

    @Test
    void testGetJobs_MultipleTenants() throws Exception {
        String tenant1 = "tenant1";
        String tenant2 = "tenant2";

        List<JobResponse> tenant1Jobs = List.of(
                createJobResponse(UUID.randomUUID(), tenant1, "target-1", JobStatus.PENDING)
        );

        List<JobResponse> tenant2Jobs = List.of(
                createJobResponse(UUID.randomUUID(), tenant2, "target-1", JobStatus.PENDING)
        );

        when(jobService.getJobsByTenant(tenant1)).thenReturn(tenant1Jobs);
        when(jobService.getJobsByTenant(tenant2)).thenReturn(tenant2Jobs);

        // Request for tenant1
        mockMvc.perform(get("/jobs")
                        .header("X-Tenant-Id", tenant1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value(tenant1));

        // Request for tenant2
        mockMvc.perform(get("/jobs")
                        .header("X-Tenant-Id", tenant2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value(tenant2));

        verify(jobService, times(1)).getJobsByTenant(tenant1);
        verify(jobService, times(1)).getJobsByTenant(tenant2);
    }

    // Helper method
    private JobResponse createJobResponse(UUID id, String tenantId, String targetId, JobStatus status) {
        return JobResponse.builder()
                .id(id)
                .tenantId(tenantId)
                .targetId(targetId)
                .payload("test payload")
                .idempotencyKey("key-" + id)
                .status(status)
                .retryCount(0)
                .maxRetries(3)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
