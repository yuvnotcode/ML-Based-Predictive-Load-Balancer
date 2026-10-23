package com.yuva.loadbalancer.service;

//(counts each request that hits a backend)

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestCountingFilter extends OncePerRequestFilter {
    private final RequestRateTracker tracker;

    public RequestCountingFilter(RequestRateTracker tracker) {
        this.tracker = tracker;
    }

    // Only count real work requests, not the metrics checks themselves.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/backend/") || path.startsWith("/backend/metrics");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        tracker.record();
        chain.doFilter(request, response);
    }
}