package com.scheduler;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * Integration tests for JobSchedulerApplication.
 * <p>
 * This class verifies that the Spring application context loads successfully,
 * the main() method can be called without exceptions, and the necessary
 * annotations for scheduling and asynchronous processing are present.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:testdb",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class JobSchedulerApplicationTest {

    /**
     * Verifies the Spring application context loads successfully,
     * exercising the class-level annotations and bean wiring.
     */
    @Test
    void contextLoads() {
        assertTrue(true, "Application context should load without errors");
    }

    /**
     * Calls main() with a mocked SpringApplication.run() so the method body is
     * covered by JaCoCo without actually starting the web server.
     */
    @Test
    void main_InvokesSpringApplicationRun_WithoutThrowingException() {
        try (MockedStatic<SpringApplication> mocked = mockStatic(SpringApplication.class)) {
            mocked.when(() -> SpringApplication.run(any(Class.class), any(String[].class)))
                  .thenReturn(null);

            assertDoesNotThrow(
                    () -> JobSchedulerApplication.main(new String[]{}),
                    "main() must not throw for an empty args array"
            );

            mocked.verify(() -> SpringApplication.run(JobSchedulerApplication.class, new String[]{}));
        }
    }

    /**
     * Verifies that @EnableScheduling is present on the application class,
     * ensuring scheduled tasks will be activated at runtime.
     */
    @Test
    void applicationClass_HasEnableSchedulingAnnotation() {
        assertTrue(
                JobSchedulerApplication.class.isAnnotationPresent(EnableScheduling.class),
                "@EnableScheduling must be declared on JobSchedulerApplication"
        );
    }

    /**
     * Verifies that @EnableAsync is present on the application class,
     * ensuring asynchronous method execution is activated at runtime.
     */
    @Test
    void applicationClass_HasEnableAsyncAnnotation() {
        assertTrue(
                JobSchedulerApplication.class.isAnnotationPresent(EnableAsync.class),
                "@EnableAsync must be declared on JobSchedulerApplication"
        );
    }

    /**
     * Verifies that the public static main(String[]) method exists and is
     * accessible via standard reflection (guards against accidental renames).
     */
    @Test
    void mainMethod_IsPublicAndStatic() throws NoSuchMethodException {
        var mainMethod = JobSchedulerApplication.class.getMethod("main", String[].class);
        assertTrue(java.lang.reflect.Modifier.isPublic(mainMethod.getModifiers()),
                "main() must be public");
        assertTrue(java.lang.reflect.Modifier.isStatic(mainMethod.getModifiers()),
                "main() must be static");
    }
}
