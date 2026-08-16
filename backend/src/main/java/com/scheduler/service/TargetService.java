package com.scheduler.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Random;

@Slf4j
@Service
public class TargetService {

    private final Random random = new Random();
    private static final double FAILURE_RATE = 0.1; // 10% failure rate

    // Simulated latency window (ms). Configurable so tests can shrink it.
    private int baseLatencyMs = 100;
    private int latencyJitterMs = 400;

    public boolean execute(String targetId, String payload) {
        try {
            // Check for forced failure payload
            if (payload != null && payload.equalsIgnoreCase("i am failing")) {
                log.info("Job with payload 'i am failing' - forcing failure");
                Thread.sleep(baseLatencyMs + random.nextInt(latencyJitterMs + 1)); // Still simulate latency
                return false;
            }

            // Simulate latency
            int latency = baseLatencyMs + random.nextInt(latencyJitterMs + 1);
            log.debug("Executing target: {} with latency: {}ms", targetId, latency);
            Thread.sleep(latency);

            // Simulate 10% failure rate
            boolean success = random.nextDouble() > FAILURE_RATE;

            if (success) {
                log.debug("Target {} executed successfully", targetId);
            } else {
                log.debug("Target {} execution failed (simulated)", targetId);
            }

            return success;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Target execution interrupted for: {}", targetId, e);
            return false;
        }
    }
}
