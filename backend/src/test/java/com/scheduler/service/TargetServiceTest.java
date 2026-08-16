package com.scheduler.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TargetServiceTest {

    @InjectMocks
    private TargetService targetService;

    @BeforeEach
    void setUp() {
        targetService = new TargetService();
        // Shrink the simulated latency so tests run fast: 1ms base, no jitter.
        ReflectionTestUtils.setField(targetService, "baseLatencyMs", 1);
        ReflectionTestUtils.setField(targetService, "latencyJitterMs", 0);
    }

    @Test
    void testExecute_ForcedFailurePayload() {
        // The "i am failing" payload should always fail
        String targetId = "target-1";
        String payload = "i am failing";

        boolean result = targetService.execute(targetId, payload);

        assertFalse(result, "Should fail with 'i am failing' payload");
    }

    @Test
    void testExecute_ForcedFailurePayload_CaseInsensitive() {
        // Test case insensitivity
        String targetId = "target-1";

        assertFalse(targetService.execute(targetId, "I AM FAILING"));
        assertFalse(targetService.execute(targetId, "i Am Failing"));
        assertFalse(targetService.execute(targetId, "I am failing"));
    }

    @Test
    void testExecute_NormalPayload_SometimesSucceeds() {
        // With 10% failure rate, we should see both successes and failures
        // over many attempts
        String targetId = "target-1";
        String payload = "normal payload";

        int successCount = 0;
        int attempts = 50;

        for (int i = 0; i < attempts; i++) {
            if (targetService.execute(targetId, payload)) {
                successCount++;
            }
        }

        // With 10% failure rate, we expect ~90% success (45 out of 50)
        // Allow range from 35-50 to account for randomness
        assertTrue(successCount >= 35 && successCount <= 50,
                String.format("Expected 35-50 successes, got %d", successCount));
    }

    @Test
    void testExecute_HandlesNullPayload() {
        String targetId = "target-1";

        // Null payload should not force failure, but may randomly fail
        // Just verify it doesn't throw exception
        assertDoesNotThrow(() -> {
            targetService.execute(targetId, null);
        });
    }

    @Test
    void testExecute_HandlesEmptyPayload() {
        String targetId = "target-1";

        // Empty string is not "i am failing", so it is treated as a normal
        // payload and should not be forced to fail over repeated attempts.
        int successCount = 0;
        for (int i = 0; i < 10; i++) {
            if (targetService.execute(targetId, "")) {
                successCount++;
            }
        }

        assertTrue(successCount > 0, "Empty payload should not be forced to fail");
    }

    @Test
    void testExecute_DifferentTargets() {
        // Different targets should work independently
        assertDoesNotThrow(() -> {
            targetService.execute("target-1", "test");
            targetService.execute("target-2", "test");
            targetService.execute("target-3", "test");
        });
    }

    @Test
    void testExecute_PartialMatchDoesNotForceFail() {
        // Only exact match "i am failing" should force failure
        String targetId = "target-1";

        // These should NOT force failure (though may randomly fail)
        // Run multiple times to verify they're not being forced to fail
        int attempts = 10;
        int successCount = 0;

        for (int i = 0; i < attempts; i++) {
            if (targetService.execute(targetId, "i am not failing")) {
                successCount++;
            }
        }

        // Should have at least some successes if not forced to fail
        assertTrue(successCount > 0, "Partial matches should not force failure");
    }

    @Test
    void testExecute_ComplexPayload() {
        String targetId = "target-1";
        String complexPayload = "{\"data\": \"value\", \"nested\": {\"key\": 123}}";

        // Complex JSON payload should work
        assertDoesNotThrow(() -> {
            targetService.execute(targetId, complexPayload);
        });
    }

    @Test
    void testExecute_LongPayload() {
        String targetId = "target-1";
        String longPayload = "a".repeat(1000);

        // Long payload should work
        assertDoesNotThrow(() -> {
            targetService.execute(targetId, longPayload);
        });
    }

    @Test
    void testExecute_SpecialCharacters() {
        String targetId = "target-1";
        String specialPayload = "!@#$%^&*()_+-=[]{}|;':\",./<>?";

        // Special characters should work
        assertDoesNotThrow(() -> {
            targetService.execute(targetId, specialPayload);
        });
    }

    @Test
    void testExecute_InterruptedDuringExecution_ReturnsFalse() {
        String targetId = "target-1";

        // Pre-set the interrupt flag so Thread.sleep throws InterruptedException
        // immediately, exercising the catch block.
        Thread.currentThread().interrupt();

        boolean result = targetService.execute(targetId, "normal payload");

        assertFalse(result, "Should return false when interrupted");
        assertTrue(Thread.interrupted(),
                "Interrupt flag should be re-set by the handler (and cleared here)");
    }

    @Test
    void testExecute_InterruptedDuringForcedFailure_ReturnsFalse() {
        String targetId = "target-1";

        Thread.currentThread().interrupt();

        boolean result = targetService.execute(targetId, "i am failing");

        assertFalse(result, "Should return false when interrupted on forced-failure path");
        // Clear the flag so it does not leak into other tests.
        Thread.interrupted();
    }

    @Test
    void testExecute_ExactFailureMatch_WithSurroundingSpaces_DoesNotForceFail() {
        // Only the exact (trimmed-equal) phrase forces failure; padded variants
        // are treated as normal payloads and can succeed.
        String targetId = "target-1";

        int successCount = 0;
        for (int i = 0; i < 10; i++) {
            if (targetService.execute(targetId, " i am failing ")) {
                successCount++;
            }
        }

        assertTrue(successCount > 0,
                "Whitespace-padded payload should not be forced to fail");
    }
}
