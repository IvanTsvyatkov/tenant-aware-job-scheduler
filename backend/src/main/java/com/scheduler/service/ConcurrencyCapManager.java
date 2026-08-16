package com.scheduler.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Slf4j
@Component
public class ConcurrencyCapManager {

    private final Semaphore globalSemaphore;
    private final int globalMax;
    private final int tenantMax;
    private final int targetMax;

    private final Map<String, Semaphore> tenantSemaphores = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> targetSemaphores = new ConcurrentHashMap<>();

    public ConcurrencyCapManager(
            @Value("${scheduler.concurrency.global-max}") int globalMax,
            @Value("${scheduler.concurrency.tenant-max}") int tenantMax,
            @Value("${scheduler.concurrency.target-max}") int targetMax) {
        this.globalSemaphore = new Semaphore(globalMax);
        this.globalMax = globalMax;
        this.tenantMax = tenantMax;
        this.targetMax = targetMax;
        log.info("Concurrency caps initialized - Global: {}, Tenant: {}, Target: {}",
                globalMax, tenantMax, targetMax);
    }

    public boolean tryAcquire(String tenantId, String targetId) {
        // Handle null inputs gracefully
        if (tenantId == null || targetId == null) {
            log.warn("Attempted to acquire with null tenantId or targetId");
            return false;
        }

        // Try to acquire all three permits atomically
        if (globalSemaphore.tryAcquire()) {
            Semaphore tenantSem = getTenantSemaphore(tenantId);
            if (tenantSem.tryAcquire()) {
                Semaphore targetSem = getTargetSemaphore(targetId);
                if (targetSem.tryAcquire()) {
                    log.debug("Acquired permits for tenant: {}, target: {}", tenantId, targetId);
                    return true;
                }
                // Failed to acquire target, release tenant
                tenantSem.release();
            }
            // Failed to acquire tenant, release global
            globalSemaphore.release();
        }

        log.debug("Failed to acquire permits for tenant: {}, target: {} - capacity limit reached",
                tenantId, targetId);
        return false;
    }

    public void release(String tenantId, String targetId) {
        // Handle null inputs gracefully
        if (tenantId == null || targetId == null) {
            log.warn("Attempted to release with null tenantId or targetId");
            return;
        }

        getTargetSemaphore(targetId).release();
        getTenantSemaphore(tenantId).release();
        globalSemaphore.release();
        log.debug("Released permits for tenant: {}, target: {}", tenantId, targetId);
    }

    private Semaphore getTenantSemaphore(String tenantId) {
        return tenantSemaphores.computeIfAbsent(tenantId, k -> new Semaphore(tenantMax));
    }

    private Semaphore getTargetSemaphore(String targetId) {
        return targetSemaphores.computeIfAbsent(targetId, k -> new Semaphore(targetMax));
    }

    public int getGlobalMax() {
        return globalMax;
    }

    public int getTenantMax() {
        return tenantMax;
    }

    public int getTargetMax() {
        return targetMax;
    }

    public int getGlobalAvailable() {
        return globalSemaphore.availablePermits();
    }

    public int getTenantAvailable(String tenantId) {
        return getTenantSemaphore(tenantId).availablePermits();
    }

    public int getTargetAvailable(String targetId) {
        return getTargetSemaphore(targetId).availablePermits();
    }
}
