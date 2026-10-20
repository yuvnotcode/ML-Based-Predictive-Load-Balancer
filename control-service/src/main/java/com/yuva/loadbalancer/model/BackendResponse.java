
package com.yuva.loadbalancer.model;

public record BackendResponse(
        String serverId,
        String message,
        int port
) {
}
