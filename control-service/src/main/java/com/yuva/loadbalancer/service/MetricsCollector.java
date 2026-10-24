package com.yuva.loadbalancer.service;

import com.yuva.loadbalancer.model.BackendMetrics;
import com.yuva.loadbalancer.model.PredictionRequest;
import com.yuva.loadbalancer.model.PredictionResponse;
import com.yuva.loadbalancer.model.ServerInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class MetricsCollector {
    private record Target(String serverId, String baseUrl) {
    }

    private static final int HISTORY_SIZE = 20;
    private static final Path CSV_PATH = Path.of("data", "metrics.csv");

    private final List<Target> targets = List.of(
            new Target("server-a", "http://localhost:9001"),
            new Target("server-b", "http://localhost:9002"),
            new Target("server-c", "http://localhost:9003")
    );

    private final Map<String, ServerInfo> latest = new HashMap<>();
    private final Map<String, Deque<Double>> cpuHistory = new HashMap<>();
    private final Map<String, Double> predictedCpu = new HashMap<>();
    private final RestTemplate restTemplate;
    private final MlClient mlClient;
    private final boolean enabled;
    private final boolean csvLogging;

    public MetricsCollector(
            @Value("${app.collector.enabled:true}") boolean enabled,
            @Value("${app.csv-logging.enabled:true}") boolean csvLogging,
            MlClient mlClient) {
        this.enabled = enabled;
        this.csvLogging = csvLogging;
        this.mlClient = mlClient;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.restTemplate = new RestTemplate(factory);

        // Until the first real measurement arrives, every server is DOWN.
        for (Target target : targets) {
            latest.put(target.serverId(),
                    new ServerInfo(target.serverId(), target.baseUrl(), "DOWN", 0, 0, 0));
            cpuHistory.put(target.serverId(), new ArrayDeque<>());
        }

        if (enabled && csvLogging) {
            prepareCsv();
        }
    }

    @Scheduled(fixedDelay = 2000)
    public void collect() {
        if (!enabled) {
            return;
        }
        for (Target target : targets) {
            try {
                BackendMetrics metrics = restTemplate.getForObject(
                        target.baseUrl() + "/backend/metrics", BackendMetrics.class);
                if (metrics == null) {
                    throw new IllegalStateException("Empty metrics response");
                }
                store(target, metrics);
                if (csvLogging) {
                    appendCsv(metrics);
                }
            } catch (Exception ex) {
                markDown(target);
            }
        }
        refreshPredictions();
    }

    // Ask the ML service once per collection round, so routing a request is only a lookup.
    private void refreshPredictions() {
        for (Target target : targets) {
            ServerInfo info;
            List<Double> recent;
            synchronized (this) {
                info = latest.get(target.serverId());
                recent = new ArrayList<>(cpuHistory.get(target.serverId()));
            }

            if (!"HEALTHY".equalsIgnoreCase(info.status())) {
                clearPrediction(target.serverId());
                continue;
            }
            while (recent.size() < 2) {
                recent.add(0, info.cpuPercent());
            }

            try {
                PredictionResponse prediction = mlClient.predict(new PredictionRequest(
                        info.serverId(),
                        info.cpuPercent(),
                        info.memoryPercent(),
                        info.requestsPerSecond(),
                        recent));
                synchronized (this) {
                    predictedCpu.put(target.serverId(), prediction.predictedLoadPercent());
                }
            } catch (Exception ex) {
                clearPrediction(target.serverId());
            }
        }
    }

    private synchronized void clearPrediction(String serverId) {
        predictedCpu.remove(serverId);
    }

    private synchronized void store(Target target, BackendMetrics m) {
        latest.put(target.serverId(), new ServerInfo(
                target.serverId(), target.baseUrl(), "HEALTHY",
                m.cpuPercent(), m.memoryPercent(), m.requestsPerSecond()));

        Deque<Double> history = cpuHistory.get(target.serverId());
        history.addLast(m.cpuPercent());
        while (history.size() > HISTORY_SIZE) {
            history.removeFirst();
        }
    }

    private synchronized void markDown(Target target) {
        latest.put(target.serverId(),
                new ServerInfo(target.serverId(), target.baseUrl(), "DOWN", 0, 0, 0));
    }

    public synchronized List<ServerInfo> getServers() {
        List<ServerInfo> result = new ArrayList<>();
        for (Target target : targets) {
            result.add(latest.get(target.serverId()));
        }
        return result;
    }

    // Recent CPU readings (oldest first).
    public synchronized List<Double> getRecentCpu(String serverId) {
        return new ArrayList<>(cpuHistory.getOrDefault(serverId, new ArrayDeque<>()));
    }

    // Latest predicted CPU for a server, or null if no fresh prediction exists.
    public synchronized Double getPredictedCpu(String serverId) {
        return predictedCpu.get(serverId);
    }

    // ---- CSV logging (training data for the ML model) ----

    private void prepareCsv() {
        try {
            Files.createDirectories(CSV_PATH.getParent());
            if (!Files.exists(CSV_PATH)) {
                Files.writeString(CSV_PATH,
                        "timestamp,server_id,cpu_percent,memory_percent,requests_per_second\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }
        } catch (IOException ex) {
            System.err.println("Could not prepare metrics CSV: " + ex.getMessage());
        }
    }

    private void appendCsv(BackendMetrics m) {
        String row = Instant.now() + "," + m.serverId() + "," + m.cpuPercent() + ","
                + m.memoryPercent() + "," + m.requestsPerSecond() + "\n";
        try {
            Files.writeString(CSV_PATH, row, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            System.err.println("Could not write metrics CSV: " + ex.getMessage());
        }
    }
}