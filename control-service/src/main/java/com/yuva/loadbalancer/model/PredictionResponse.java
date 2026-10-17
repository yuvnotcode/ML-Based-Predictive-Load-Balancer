package com.yuva.loadbalancer.model;

public record PredictionResponse(
        String serverId,
        double currentLoadPercent,
        double predictedLoadPercent,
        int predictionHorizonSeconds,
        String modelVersion,
        String method
) {}
