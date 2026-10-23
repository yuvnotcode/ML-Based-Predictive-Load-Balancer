package com.yuva.loadbalancer.service;

import com.yuva.loadbalancer.model.PredictionRequest;
import com.yuva.loadbalancer.model.PredictionResponse;
import com.yuva.loadbalancer.model.ServerInfo;
import com.yuva.loadbalancer.model.ServerSelectionResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ServerSelectionService {
    private final MlClient mlClient;
    private final MetricsCollector metricsCollector;

    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public ServerSelectionService(MlClient mlClient, MetricsCollector metricsCollector) {
        this.mlClient = mlClient;
        this.metricsCollector = metricsCollector;
    }

    public ServerSelectionResponse selectBest(List<ServerInfo> servers) {

        // Step 1: Keep only healthy servers.
        List<ServerInfo> healthyServers = healthyOnly(servers);

        // Step 2: Find the highest request rate among healthy servers.
        double maxRps = healthyServers.stream()
                .mapToDouble(ServerInfo::requestsPerSecond)
                .max()
                .orElse(0.0);

        // Step 3: Calculate a score for every healthy server.
        return healthyServers.stream()
                .map(server -> {
                    double normalizedRps = maxRps > 0
                            ? (server.requestsPerSecond() / maxRps) * 100.0
                            : 0.0;

                    double score =
                            0.5 * server.cpuPercent()
                                    + 0.3 * server.memoryPercent()
                                    + 0.2 * normalizedRps;

                    return new ScoredServer(server, score);
                })

                // Step 4: Lowest score wins; server ID breaks ties.
                .min(Comparator
                        .comparingDouble(ScoredServer::score)
                        .thenComparing(item -> item.server().serverId()))
                .map(item -> new ServerSelectionResponse(
                        item.server(),
                        Math.round(item.score() * 100.0) / 100.0,
                        "LOWEST_WEIGHTED_LOAD"
                ))
                .orElseThrow();
    }

    public ServerInfo selectRoundRobin(List<ServerInfo> servers) {
        List<ServerInfo> healthyServers = healthyOnly(servers);

        int index = Math.floorMod(
                roundRobinCounter.getAndIncrement(),
                healthyServers.size()
        );

        return healthyServers.get(index);
    }

    public ServerSelectionResponse selectBestPredicted(List<ServerInfo> servers) {
        List<ServerInfo> healthyServers = healthyOnly(servers);

        try {
            return healthyServers.stream()
                    .map(server -> {
                        PredictionRequest request = new PredictionRequest(
                                server.serverId(),
                                server.cpuPercent(),
                                server.memoryPercent(),
                                server.requestsPerSecond(),
                                recentCpuFor(server)
                        );

                        PredictionResponse prediction = mlClient.predict(request);

                        return new ScoredServer(server, prediction.predictedLoadPercent());
                    })
                    .min(Comparator
                            .comparingDouble(ScoredServer::score)
                            .thenComparing(item -> item.server().serverId()))
                    .map(item -> new ServerSelectionResponse(
                            item.server(),
                            Math.round(item.score() * 100.0) / 100.0,
                            "PREDICTIVE_ML_BASELINE"
                    ))
                    .orElseThrow();
        } catch (Exception ex) {
            // ML service is down or failed: do not break routing, use current load instead.
            ServerSelectionResponse fallback = selectBest(servers);
            return new ServerSelectionResponse(
                    fallback.selectedServer(),
                    fallback.loadScore(),
                    "FALLBACK_LOWEST_WEIGHTED_LOAD"
            );
        }
    }

    // Real recent CPU readings; the ML service needs at least 2 and at most 20.
    private List<Double> recentCpuFor(ServerInfo server) {
        List<Double> history = new ArrayList<>(metricsCollector.getRecentCpu(server.serverId()));
        while (history.size() < 2) {
            history.add(0, server.cpuPercent());
        }
        return history;
    }

    private List<ServerInfo> healthyOnly(List<ServerInfo> servers) {
        List<ServerInfo> healthyServers = servers.stream()
                .filter(server -> "HEALTHY".equalsIgnoreCase(server.status()))
                .toList();

        if (healthyServers.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No healthy servers are available"
            );
        }
        return healthyServers;
    }

    private record ScoredServer(ServerInfo server, double score) {
    }
}