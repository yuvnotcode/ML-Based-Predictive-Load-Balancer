package com.yuva.loadbalancer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuva.loadbalancer.model.PredictionRequest;
import com.yuva.loadbalancer.model.PredictionResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class MlClient {
    private final RestClient restClient;

    public MlClient(@Value("${ml-service.base-url}") String baseUrl) {
        // Plain HTTP/1.1 client (no HTTP/2 upgrade) so Uvicorn accepts the request body.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public PredictionResponse predict(PredictionRequest request) {
        return restClient.post()
                .uri("/predict")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(PredictionResponse.class);
    }

    public JsonNode health() {
        return restClient.get()
                .uri("/health")
                .retrieve()
                .body(JsonNode.class);
    }
}