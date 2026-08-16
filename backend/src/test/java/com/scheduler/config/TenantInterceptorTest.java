package com.scheduler.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantInterceptorTest {

    @InjectMocks
    private TenantInterceptor tenantInterceptor;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private Object handler;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void testPreHandle_WithValidTenantHeader() {
        // Arrange
        String tenantId = "tenant1";
        when(request.getHeader("X-Tenant-Id")).thenReturn(tenantId);

        // Act
        boolean result = tenantInterceptor.preHandle(request, response, handler);

        // Assert
        assertTrue(result, "preHandle should return true");
        assertEquals(tenantId, TenantContext.getTenantId());
        verify(request).getHeader("X-Tenant-Id");
    }

    @Test
    void testPreHandle_WithNullTenantHeader() {
        // Arrange
        when(request.getHeader("X-Tenant-Id")).thenReturn(null);

        // Act
        boolean result = tenantInterceptor.preHandle(request, response, handler);

        // Assert
        assertTrue(result, "preHandle should return true even without tenant header");
        assertNull(TenantContext.getTenantId(), "Tenant should not be set when header is null");
        verify(request).getHeader("X-Tenant-Id");
    }

    @Test
    void testPreHandle_WithEmptyTenantHeader() {
        // Arrange
        when(request.getHeader("X-Tenant-Id")).thenReturn("");

        // Act
        boolean result = tenantInterceptor.preHandle(request, response, handler);

        // Assert
        assertTrue(result, "preHandle should return true");
        assertNull(TenantContext.getTenantId(), "Tenant should not be set when header is empty");
        verify(request).getHeader("X-Tenant-Id");
    }

    @Test
    void testPreHandle_WithWhitespaceTenantHeader() {
        // Arrange
        String whitespaceHeader = "   ";
        when(request.getHeader("X-Tenant-Id")).thenReturn(whitespaceHeader);

        // Act
        boolean result = tenantInterceptor.preHandle(request, response, handler);

        // Assert
        assertTrue(result);
        assertEquals(whitespaceHeader, TenantContext.getTenantId(),
            "Should set tenant even with whitespace (not trimmed)");
    }

    @Test
    void testAfterCompletion_ClearsTenantContext() {
        // Arrange
        TenantContext.setTenantId("tenant1");
        assertNotNull(TenantContext.getTenantId());

        // Act
        tenantInterceptor.afterCompletion(request, response, handler, null);

        // Assert
        assertNull(TenantContext.getTenantId(), "Tenant context should be cleared");
    }

    @Test
    void testAfterCompletion_WithException() {
        // Arrange
        TenantContext.setTenantId("tenant1");
        Exception exception = new RuntimeException("Test exception");

        // Act
        tenantInterceptor.afterCompletion(request, response, handler, exception);

        // Assert
        assertNull(TenantContext.getTenantId(),
            "Tenant context should be cleared even when exception occurred");
    }

    @Test
    void testAfterCompletion_WhenTenantNotSet() {
        // Arrange - no tenant set
        assertNull(TenantContext.getTenantId());

        // Act
        assertDoesNotThrow(() -> {
            tenantInterceptor.afterCompletion(request, response, handler, null);
        }, "Should not throw when tenant was not set");

        // Assert
        assertNull(TenantContext.getTenantId());
    }

    @Test
    void testFullRequestLifecycle() {
        // Arrange
        String tenantId = "tenant1";
        when(request.getHeader("X-Tenant-Id")).thenReturn(tenantId);

        // Act - preHandle
        boolean preHandleResult = tenantInterceptor.preHandle(request, response, handler);

        // Assert - during request
        assertTrue(preHandleResult);
        assertEquals(tenantId, TenantContext.getTenantId());

        // Act - afterCompletion
        tenantInterceptor.afterCompletion(request, response, handler, null);

        // Assert - after request
        assertNull(TenantContext.getTenantId());
    }

    @Test
    void testPreHandle_OverwritesPreviousTenant() {
        // Arrange
        TenantContext.setTenantId("old-tenant");
        String newTenant = "new-tenant";
        when(request.getHeader("X-Tenant-Id")).thenReturn(newTenant);

        // Act
        tenantInterceptor.preHandle(request, response, handler);

        // Assert
        assertEquals(newTenant, TenantContext.getTenantId());
    }

    @Test
    void testPreHandle_WithSpecialCharactersInTenant() {
        // Arrange
        String specialTenant = "tenant-1_test@example.com";
        when(request.getHeader("X-Tenant-Id")).thenReturn(specialTenant);

        // Act
        boolean result = tenantInterceptor.preHandle(request, response, handler);

        // Assert
        assertTrue(result);
        assertEquals(specialTenant, TenantContext.getTenantId());
    }

    @Test
    void testPreHandle_WithNumericTenant() {
        // Arrange
        String numericTenant = "12345";
        when(request.getHeader("X-Tenant-Id")).thenReturn(numericTenant);

        // Act
        boolean result = tenantInterceptor.preHandle(request, response, handler);

        // Assert
        assertTrue(result);
        assertEquals(numericTenant, TenantContext.getTenantId());
    }

    @Test
    void testPreHandle_WithVeryLongTenant() {
        // Arrange
        String longTenant = "a".repeat(1000);
        when(request.getHeader("X-Tenant-Id")).thenReturn(longTenant);

        // Act
        boolean result = tenantInterceptor.preHandle(request, response, handler);

        // Assert
        assertTrue(result);
        assertEquals(longTenant, TenantContext.getTenantId());
    }

    @Test
    void testMultipleRequestsInSameThread() {
        // Simulate multiple requests processed by same thread
        String tenant1 = "tenant1";
        String tenant2 = "tenant2";

        // Request 1
        when(request.getHeader("X-Tenant-Id")).thenReturn(tenant1);
        tenantInterceptor.preHandle(request, response, handler);
        assertEquals(tenant1, TenantContext.getTenantId());
        tenantInterceptor.afterCompletion(request, response, handler, null);
        assertNull(TenantContext.getTenantId());

        // Request 2
        when(request.getHeader("X-Tenant-Id")).thenReturn(tenant2);
        tenantInterceptor.preHandle(request, response, handler);
        assertEquals(tenant2, TenantContext.getTenantId());
        tenantInterceptor.afterCompletion(request, response, handler, null);
        assertNull(TenantContext.getTenantId());
    }

    @Test
    void testAfterCompletion_CalledMultipleTimes() {
        // Arrange
        TenantContext.setTenantId("tenant1");

        // Act
        tenantInterceptor.afterCompletion(request, response, handler, null);
        tenantInterceptor.afterCompletion(request, response, handler, null);

        // Assert
        assertDoesNotThrow(() ->
            tenantInterceptor.afterCompletion(request, response, handler, null)
        );
        assertNull(TenantContext.getTenantId());
    }
}
