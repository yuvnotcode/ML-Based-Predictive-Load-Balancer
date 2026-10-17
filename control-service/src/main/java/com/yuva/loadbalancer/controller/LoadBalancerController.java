package com.yuva.loadbalancer.controller;

import com.yuva.loadbalancer.model.PredictionRequest;
import com.yuva.loadbalancer.model.PredictionResponse;
import com.yuva.loadbalancer.model.ServerInfo;
import com.yuva.loadbalancer.service.MlClient;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.yuva.loadbalancer.model.ServerSelectionResponse;
import com.yuva.loadbalancer.service.ServerSelectionService;

@RestController
@RequestMapping("/api")
public class LoadBalancerController {
    private final MlClient mlClient;

    private final ServerSelectionService serverSelectionService;

    public LoadBalancerController(
            MlClient mlClient,
            ServerSelectionService serverSelectionService
    ) {
        this.mlClient = mlClient;
        this.serverSelectionService = serverSelectionService;
    }

    @GetMapping("/servers")
    public List<ServerInfo> servers() {
        // Simulated metrics for API-contract testing. Replace with collected measurements.
        return List.of(
                new ServerInfo("server-a", "http://localhost:9001", "HEALTHY", 38, 45, 22),
                new ServerInfo("server-b", "http://localhost:9002", "HEALTHY", 61, 53, 37),
                new ServerInfo("server-c", "http://localhost:9003", "HEALTHY", 74, 68, 49)
        );
    }
    @GetMapping("/servers/recommended")
    public ServerSelectionResponse recommendedServer() {
        return serverSelectionService.selectBest(servers());
    }

    @GetMapping("/ml-health")
    public String mlHealth() {
        return mlClient.health();
    }

    @PostMapping("/predictions")
    public PredictionResponse predict(@Valid @RequestBody PredictionRequest request) {
        return mlClient.predict(request);
    }
}
