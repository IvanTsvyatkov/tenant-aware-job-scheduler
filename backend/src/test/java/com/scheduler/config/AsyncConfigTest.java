package com.scheduler.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class AsyncConfigTest {

    private final AsyncConfig asyncConfig = new AsyncConfig();

    @Test
    void testJobExecutor_CreatesExecutor() {
        // Act
        Executor executor = asyncConfig.jobExecutor();

        // Assert
        assertNotNull(executor, "Executor should not be null");
        assertTrue(executor instanceof ThreadPoolTaskExecutor,
            "Executor should be ThreadPoolTaskExecutor");
    }

    @Test
    void testJobExecutor_ConfiguresProperties() {
        // Act
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) asyncConfig.jobExecutor();

        // Assert
        assertEquals(50, executor.getCorePoolSize(),
            "Core pool size should be 50");
        assertEquals(50, executor.getMaxPoolSize(),
            "Max pool size should be 50");
        assertEquals("job-exec-", executor.getThreadNamePrefix(),
            "Thread name prefix should be 'job-exec-'");
        // Queue capacity and shutdown settings can't be read back after
        // initialization, but the executor should be initialized with a
        // backing thread pool.
        assertNotNull(executor.getThreadPoolExecutor(),
            "Executor should be initialized and have thread pool");
    }

    @Test
    void testJobExecutor_CanExecuteTasks() throws InterruptedException {
        // Arrange
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) asyncConfig.jobExecutor();
        final boolean[] taskExecuted = {false};

        // Act
        executor.execute(() -> {
            taskExecuted[0] = true;
        });

        Thread.sleep(100); // Wait for async execution

        // Assert
        assertTrue(taskExecuted[0], "Task should be executed");
    }

    @Test
    void testJobExecutor_HandlesMultipleTasks() throws InterruptedException {
        // Arrange
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) asyncConfig.jobExecutor();
        int taskCount = 10;
        final int[] completedTasks = {0};

        // Act
        for (int i = 0; i < taskCount; i++) {
            executor.execute(() -> {
                synchronized (completedTasks) {
                    completedTasks[0]++;
                }
            });
        }

        Thread.sleep(200); // Wait for all tasks

        // Assert
        assertEquals(taskCount, completedTasks[0],
            "All tasks should be executed");
    }

    @Test
    void testJobExecutor_CreatesNewInstanceEachTime() {
        // Act
        Executor executor1 = asyncConfig.jobExecutor();
        Executor executor2 = asyncConfig.jobExecutor();

        // Assert
        assertNotSame(executor1, executor2,
            "Each call should create a new executor instance");
    }

    @Test
    void testJobExecutor_ThreadNaming() throws InterruptedException {
        // Arrange
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) asyncConfig.jobExecutor();
        final String[] threadName = new String[1];

        // Act
        executor.execute(() -> {
            threadName[0] = Thread.currentThread().getName();
        });

        Thread.sleep(100);

        // Assert
        assertNotNull(threadName[0]);
        assertTrue(threadName[0].startsWith("job-exec-"),
            "Thread name should start with 'job-exec-', got: " + threadName[0]);
    }

    @Test
    void testJobExecutor_HandlesExceptionInTask() throws InterruptedException {
        // Arrange
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) asyncConfig.jobExecutor();
        final boolean[] secondTaskRan = {false};

        // Act - first task throws exception
        executor.execute(() -> {
            throw new RuntimeException("Test exception");
        });

        // Second task should still run
        executor.execute(() -> {
            secondTaskRan[0] = true;
        });

        Thread.sleep(200);

        // Assert
        assertTrue(secondTaskRan[0],
            "Executor should continue processing tasks after exception");
    }
}
