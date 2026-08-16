package com.scheduler.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // JobController (/jobs): POST create, GET list, GET /{jobId}, GET /stream
        for (String pattern : new String[]{"/jobs", "/jobs/**"}) {
            registry.addMapping(pattern)
                    .allowedOriginPatterns("http://localhost:*", "file://*")
                    .allowedMethods("GET", "POST")
                    .allowedHeaders("X-Tenant-Id", "Content-Type")
                    .allowCredentials(true)
                    .maxAge(3600);
        }

        // ConfigController (/config): read-only GET /concurrency
        for (String pattern : new String[]{"/config", "/config/**"}) {
            registry.addMapping(pattern)
                    .allowedOriginPatterns("http://localhost:*", "file://*")
                    .allowedMethods("GET")
                    .allowedHeaders("X-Tenant-Id", "Content-Type")
                    .allowCredentials(true)
                    .maxAge(3600);
        }
    }
}
