package com.scheduler.service;

import com.scheduler.dto.JobResponse;
import com.scheduler.model.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Slf4j
@Service
@RequiredArgsConstructor
public class SseService {

    private final Map<String, Set<SseEmitter>> tenantEmitters = new ConcurrentHashMap<>();

    public SseEmitter addEmitter(String tenantId) {
        SseEmitter emitter = new SseEmitter(0L); // No timeout - use heartbeat instead

        Set<SseEmitter> emitters = tenantEmitters.computeIfAbsent(
                tenantId, k -> new CopyOnWriteArraySet<>());
        emitters.add(emitter);

        emitter.onCompletion(() -> removeEmitter(tenantId, emitter));
        emitter.onTimeout(() -> removeEmitter(tenantId, emitter));
        emitter.onError((e) -> removeEmitter(tenantId, emitter));

        log.info("Added SSE emitter for tenant: {}, total emitters: {}", tenantId, emitters.size());

        // Send initial event to establish connection
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("SSE connection established for tenant: " + tenantId));
        } catch (IOException e) {
            log.error("Failed to send initial SSE event for tenant: {}", tenantId, e);
            removeEmitter(tenantId, emitter);
        }

        return emitter;
    }

    public void removeEmitter(String tenantId, SseEmitter emitter) {
        Set<SseEmitter> emitters = tenantEmitters.get(tenantId);
        if (emitters != null) {
            emitters.remove(emitter);
            log.info("Removed SSE emitter for tenant: {}, remaining: {}", tenantId, emitters.size());
        }
    }

    // Send heartbeat every 15 seconds to keep connections alive
    @Scheduled(fixedRate = 15000)
    public void sendHeartbeat() {
        for (Map.Entry<String, Set<SseEmitter>> entry : tenantEmitters.entrySet()) {
            String tenantId = entry.getKey();
            Set<SseEmitter> emitters = entry.getValue();

            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("heartbeat")
                            .data("ping"));
                    log.trace("Sent heartbeat to tenant: {}", tenantId);
                } catch (IOException e) {
                    log.debug("Failed to send heartbeat to tenant {}, removing emitter", tenantId);
                    removeEmitter(tenantId, emitter);
                }
            }
        }
    }

    public void publishJobUpdate(Job job) {
        String tenantId = job.getTenantId();
        Set<SseEmitter> emitters = tenantEmitters.get(tenantId);

        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        JobResponse jobResponse = toResponse(job);

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("job-update")
                        .data(jobResponse));
                log.debug("Sent SSE update for job {} to tenant {}", job.getId(), tenantId);
            } catch (IOException e) {
                log.warn("Failed to send SSE update to tenant {}, removing emitter", tenantId, e);
                removeEmitter(tenantId, emitter);
            }
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
