package com.yuva.loadbalancer.model;

public record ServerInfo(
        String serverId,
        String baseUrl,
        String status,
        double cpuPercent,
        double memoryPercent,
        double requestsPerSecond
) {}
