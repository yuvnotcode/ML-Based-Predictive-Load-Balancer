package com.yuva.loadbalancer.service;

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
    private final MetricsCollector metricsCollector;

    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public ServerSelectionService(MetricsCollector metricsCollector) {
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

        List<ScoredServer> scored = new ArrayList<>();
        for (ServerInfo server : healthyServers) {
            Double predicted = metricsCollector.getPredictedCpu(server.serverId());
            if (predicted == null) {
                // No fresh prediction (ML service down or not ready yet): use current load.
                ServerSelectionResponse fallback = selectBest(servers);
                return new ServerSelectionResponse(
                        fallback.selectedServer(),
                        fallback.loadScore(),
                        "FALLBACK_LOWEST_WEIGHTED_LOAD"
                );
            }
            scored.add(new ScoredServer(server, predicted));
        }

        ScoredServer best = scored.stream()
                .min(Comparator
                        .comparingDouble(ScoredServer::score)
                        .thenComparing(item -> item.server().serverId()))
                .orElseThrow();

        return new ServerSelectionResponse(
                best.server(),
                Math.round(best.score() * 100.0) / 100.0,
                "PREDICTIVE_ML_MODEL"
        );
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