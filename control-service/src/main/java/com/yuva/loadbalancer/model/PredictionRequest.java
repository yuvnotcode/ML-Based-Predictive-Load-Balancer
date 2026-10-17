package com.yuva.loadbalancer.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public record PredictionRequest(
        @NotBlank String serverId,
        @DecimalMin("0.0") @DecimalMax("100.0") double cpuPercent,
        @DecimalMin("0.0") @DecimalMax("100.0") double memoryPercent,
        @PositiveOrZero double requestsPerSecond,
        @NotEmpty List<@DecimalMin("0.0") @DecimalMax("100.0") Double> recentCpuPercent
) {}
