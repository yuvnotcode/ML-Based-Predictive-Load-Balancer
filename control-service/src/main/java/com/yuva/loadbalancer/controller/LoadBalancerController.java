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
import com.yuva.loadbalancer.model.BackendResponse;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.JsonNode;
import com.yuva.loadbalancer.service.MetricsCollector;

@RestController
@RequestMapping("/api")
public class LoadBalancerController {
    private final MlClient mlClient;

    private final ServerSelectionService serverSelectionService;

    private final MetricsCollector metricsCollector;

    private final RestTemplate restTemplate = new RestTemplate();

    public LoadBalancerController(
            MlClient mlClient,
            ServerSelectionService serverSelectionService,
            MetricsCollector metricsCollector
    ) {
        this.mlClient = mlClient;
        this.serverSelectionService = serverSelectionService;
        this.metricsCollector = metricsCollector;
    }
//    Round robin algo mapping
    @GetMapping("/servers/round-robin")
    public ServerInfo roundRobinServer() {
        return serverSelectionService.selectRoundRobin(servers());
    }

    @GetMapping("/servers")
    public List<ServerInfo> servers() {
        return metricsCollector.getServers();
    }

    @GetMapping("/servers/recommended")
    public ServerSelectionResponse recommendedServer() {
        return serverSelectionService.selectBest(servers());
    }


@GetMapping("/ml-health")
public JsonNode mlHealth() {
    return mlClient.health();
}

    @GetMapping("/route/predictive")
    public BackendResponse predictiveRouteRequest() {
        ServerSelectionResponse selection =
                serverSelectionService.selectBestPredicted(servers());

        ServerInfo selectedServer = selection.selectedServer();

        String backendUrl =
                selectedServer.baseUrl() + "/backend/info";

        try {
            BackendResponse response = restTemplate.getForObject(
                    backendUrl,
                    BackendResponse.class
            );

            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Backend returned an empty response"
                );
            }

            return response;

        } catch (org.springframework.web.client.RestClientException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Failed to communicate with backend: "
                            + selectedServer.serverId(),
                    ex
            );
        }
    }

    @PostMapping("/predictions")
    public PredictionResponse predict(@Valid @RequestBody PredictionRequest request) {
        return mlClient.predict(request);
    }

    @GetMapping("/route")
    public BackendResponse routeRequest() {
        // 1. Select a healthy backend using round robin
        ServerInfo selectedServer =
                serverSelectionService.selectRoundRobin(servers());

        // 2. Build the backend URL
        String backendUrl =
                selectedServer.baseUrl() + "/backend/info";

        // 3. Send a real HTTP GET request to that backend
        try {
            BackendResponse response =
                    restTemplate.getForObject(
                            backendUrl,
                            BackendResponse.class
                    );

            // 4. Return the backend's response to the client
            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Backend returned an empty response"
                );
            }

            return response;

        } catch (org.springframework.web.client.RestClientException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Failed to communicate with backend: "
                            + selectedServer.serverId(),
                    ex
            );
        }
    }
}
