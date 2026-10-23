package com.yuva.loadbalancer.service;

//(counts how many requests per second a backend is getting)

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RequestRateTracker {
    private final AtomicLong total = new AtomicLong();
    private long lastTotal = 0;
    private volatile double requestsPerSecond = 0;

    public void record() {
        total.incrementAndGet();
    }

    // Runs once per second and smooths the number so it doesn't jump around.
    @Scheduled(fixedRate = 1000)
    void tick() {
        long now = total.get();
        double delta = now - lastTotal;
        lastTotal = now;
        requestsPerSecond = 0.5 * requestsPerSecond + 0.5 * delta;
    }

    public double getRequestsPerSecond() {
        return requestsPerSecond;
    }
}