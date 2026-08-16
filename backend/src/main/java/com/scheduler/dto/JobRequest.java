package com.scheduler.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobRequest {

    @NotBlank(message = "Tenant ID is required")
    private String tenantId;

    @NotBlank(message = "Target ID is required")
    private String targetId;

    private String payload;

    @NotBlank(message = "Idempotency key is required")
    private String idempotencyKey;
}
