package com.yuva.loadbalancer.service;

import com.yuva.loadbalancer.model.PredictionRequest;
import com.yuva.loadbalancer.model.PredictionResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class MlClient {
    private final RestClient restClient;

    public MlClient(@Value("${ml-service.base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public PredictionResponse predict(PredictionRequest request) {
        return restClient.post()
                .uri("/predict")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(PredictionResponse.class);
    }

    public String health() {
        return restClient.get().uri("/health").retrieve().body(String.class);
    }
}
