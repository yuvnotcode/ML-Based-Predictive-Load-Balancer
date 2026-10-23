package com.yuva.loadbalancer.model;

public record BackendMetrics(
        String serverId,
        double cpuPercent,
        double memoryPercent,
        double requestsPerSecond
) {
}