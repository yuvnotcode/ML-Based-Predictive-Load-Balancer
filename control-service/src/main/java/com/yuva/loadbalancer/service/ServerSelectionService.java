
package com.yuva.loadbalancer.service;

import com.yuva.loadbalancer.model.ServerInfo;
import com.yuva.loadbalancer.model.ServerSelectionResponse;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ServerSelectionService {
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);

    public ServerSelectionResponse selectBest(List<ServerInfo> servers) {

        // Step 1: Keep only healthy servers.
        List<ServerInfo> healthyServers = servers.stream()
                .filter(server ->
                        "HEALTHY".equalsIgnoreCase(server.status()))
                .toList();

        // Step 2: Fail safely if no healthy server is available.
        if (healthyServers.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No healthy servers are available"
            );
        }

        // Step 3: Find the highest request rate among healthy servers.
        double maxRps = healthyServers.stream()
                .mapToDouble(ServerInfo::requestsPerSecond)
                .max()
                .orElse(0.0);

        // Step 4: Calculate a score for every healthy server.
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

                // Step 5: Lowest score wins; server ID breaks ties.
                .min(Comparator
                        .comparingDouble(ScoredServer::score)
                        .thenComparing(
                                item -> item.server().serverId()
                        ))
                .map(item -> new ServerSelectionResponse(
                        item.server(),
                        Math.round(item.score() * 100.0) / 100.0,
                        "LOWEST_WEIGHTED_LOAD"
                ))
                .orElseThrow();
    }
    public ServerInfo selectRoundRobin(List<ServerInfo> servers) {

        // 1. Keep only healthy servers.
        List<ServerInfo> healthyServers = servers.stream()
                .filter(server ->
                        "HEALTHY".equalsIgnoreCase(server.status()))
                .toList();

        // 2. Fail if no healthy server exists.
        if (healthyServers.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No healthy servers are available"
            );
        }

        // 3. Select the next server in the sequence.
        int index = Math.floorMod(
                roundRobinCounter.getAndIncrement(),
                healthyServers.size()
        );

        return healthyServers.get(index);
    }

    private record ScoredServer(ServerInfo server, double score) {
    }
}
