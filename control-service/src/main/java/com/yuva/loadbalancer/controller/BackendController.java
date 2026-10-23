package com.yuva.loadbalancer.controller;

import com.yuva.loadbalancer.model.BackendMetrics;
import com.yuva.loadbalancer.model.BackendResponse;
import com.yuva.loadbalancer.service.RequestRateTracker;
import java.lang.management.ManagementFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BackendController {

    @Value("${app.server-id:backend-default}")
    private String serverId;

    @Value("${server.port:8080}")
    private int port;

    private final RequestRateTracker tracker;

    public BackendController(RequestRateTracker tracker) {
        this.tracker = tracker;
    }

    @GetMapping("/backend/info")
    public BackendResponse info() {
        return new BackendResponse(
                serverId,
                "Request successfully reached the backend",
                port
        );
    }

    // Real measurements of THIS backend process.
    @GetMapping("/backend/metrics")
    public BackendMetrics metrics() {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean();

        double cpu = Math.max(0.0, os.getProcessCpuLoad()) * 100.0;

        Runtime runtime = Runtime.getRuntime();
        double usedHeap = runtime.totalMemory() - runtime.freeMemory();
        double memory = usedHeap / runtime.maxMemory() * 100.0;

        return new BackendMetrics(
                serverId,
                round(cpu),
                round(memory),
                round(tracker.getRequestsPerSecond())
        );
    }

    // Burns CPU for a while, so we can create artificial load for testing.
    @GetMapping("/backend/work")
    public BackendResponse work(@RequestParam(defaultValue = "200") long ms) {
        long end = System.nanoTime() + Math.min(ms, 5000) * 1_000_000L;
        double sink = 0;
        while (System.nanoTime() < end) {
            sink += Math.sqrt(sink + 1);
        }
        return new BackendResponse(serverId, "Worked for " + ms + " ms", port);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}