package com.scheduler.controller;

import com.scheduler.service.ConcurrencyCapManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes read-only runtime configuration to the frontend so that values such
 * as concurrency caps stay in sync with the backend application.yml.
 */
@RestController
@RequestMapping("/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConcurrencyCapManager concurrencyCapManager;

    @GetMapping("/concurrency")
    public ResponseEntity<Map<String, Integer>> getConcurrencyCaps() {
        Map<String, Integer> caps = new LinkedHashMap<>();
        caps.put("globalMax", concurrencyCapManager.getGlobalMax());
        caps.put("tenantMax", concurrencyCapManager.getTenantMax());
        caps.put("targetMax", concurrencyCapManager.getTargetMax());
        return ResponseEntity.ok(caps);
    }
}
