package com.scheduler.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebConfigTest {

    @Mock
    private TenantInterceptor tenantInterceptor;

    @InjectMocks
    private WebConfig webConfig;

    @Mock
    private InterceptorRegistry interceptorRegistry;

    @Mock
    private InterceptorRegistration interceptorRegistration;

    @Mock
    private CorsRegistry corsRegistry;

    @Mock
    private CorsRegistration corsRegistration;

    @Captor
    private ArgumentCaptor<String> pathCaptor;

    @Test
    void testAddInterceptors() {
        // Arrange
        when(interceptorRegistry.addInterceptor(any())).thenReturn(interceptorRegistration);

        // Act
        webConfig.addInterceptors(interceptorRegistry);

        // Assert
        verify(interceptorRegistry).addInterceptor(tenantInterceptor);
    }

    @Test
    void testAddCorsMappings() {
        // Arrange
        when(corsRegistry.addMapping(anyString())).thenReturn(corsRegistration);
        when(corsRegistration.allowedOriginPatterns(anyString(), anyString())).thenReturn(corsRegistration);
        when(corsRegistration.allowedMethods(any(String[].class))).thenReturn(corsRegistration);
        when(corsRegistration.allowedHeaders(anyString(), anyString())).thenReturn(corsRegistration);
        when(corsRegistration.allowCredentials(anyBoolean())).thenReturn(corsRegistration);
        when(corsRegistration.maxAge(anyLong())).thenReturn(corsRegistration);

        // Act
        webConfig.addCorsMappings(corsRegistry);

        // Assert - only the endpoints that actually exist are exposed
        // Both exact base paths and sub-paths are mapped so preflight matches.
        verify(corsRegistry).addMapping("/jobs");
        verify(corsRegistry).addMapping("/jobs/**");
        verify(corsRegistry).addMapping("/config");
        verify(corsRegistry).addMapping("/config/**");
        verify(corsRegistration, times(4)).allowedOriginPatterns("http://localhost:*", "file://*");
        // /jobs* allows GET + POST (x2), /config* allows GET only (x2)
        verify(corsRegistration, times(2)).allowedMethods("GET", "POST");
        verify(corsRegistration, times(2)).allowedMethods("GET");
        verify(corsRegistration, times(4)).allowedHeaders("X-Tenant-Id", "Content-Type");
        verify(corsRegistration, times(4)).allowCredentials(true);
        verify(corsRegistration, times(4)).maxAge(3600);
        // Wildcard exposed headers should no longer be used
        verify(corsRegistration, never()).exposedHeaders(anyString());
    }

    @Test
    void testWebConfig_TenantInterceptorNotNull() {
        // Assert
        assertNotNull(webConfig, "WebConfig should be created");
    }

    @Test
    void testAddInterceptors_CalledMultipleTimes() {
        // Arrange
        when(interceptorRegistry.addInterceptor(any())).thenReturn(interceptorRegistration);

        // Act
        webConfig.addInterceptors(interceptorRegistry);
        webConfig.addInterceptors(interceptorRegistry);

        // Assert
        verify(interceptorRegistry, times(2)).addInterceptor(tenantInterceptor);
    }

    @Test
    void testAddCorsMappings_ConfiguresAllSettings() {
        // Arrange
        when(corsRegistry.addMapping(anyString())).thenReturn(corsRegistration);
        when(corsRegistration.allowedOriginPatterns(anyString(), anyString())).thenReturn(corsRegistration);
        when(corsRegistration.allowedMethods(any(String[].class))).thenReturn(corsRegistration);
        when(corsRegistration.allowedHeaders(anyString(), anyString())).thenReturn(corsRegistration);
        when(corsRegistration.allowCredentials(anyBoolean())).thenReturn(corsRegistration);
        when(corsRegistration.maxAge(anyLong())).thenReturn(corsRegistration);

        // Act
        webConfig.addCorsMappings(corsRegistry);

        // Assert - both mappings configure origins, headers, credentials and max-age
        verify(corsRegistration, times(4)).allowedOriginPatterns("http://localhost:*", "file://*");
        verify(corsRegistration, times(4)).allowedHeaders("X-Tenant-Id", "Content-Type");
        verify(corsRegistration, times(4)).allowCredentials(true);
        verify(corsRegistration, times(4)).maxAge(3600);
        verify(corsRegistration, never()).exposedHeaders(anyString());
    }
}
