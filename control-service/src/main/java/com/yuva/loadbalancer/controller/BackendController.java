
package com.yuva.loadbalancer.controller;

import com.yuva.loadbalancer.model.BackendResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BackendController {

    @Value("${app.server-id:backend-default}")
    private String serverId;

    @Value("${server.port:8080}")
    private int port;

    @GetMapping("/backend/info")
    public BackendResponse info() {
        return new BackendResponse(
                serverId,
                "Request successfully reached the backend",
                port
        );
    }
}
