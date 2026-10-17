package com.yuva.loadbalancer.model;

public record ServerSelectionResponse(
        ServerInfo selectedServer,
        double loadScore,
        String strategy
) {
}
