package com.doggeon.jobrecommendation.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class RequestLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Generate locally; untrusted inbound headers must never become log content.
        String requestId = UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", requestId);
        MDC.put("requestId", requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // Log the route template rather than query strings, raw paths or submitted profile data.
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            log.info("http_request method={} route={} status={} duration_ms={}",
                    request.getMethod(), route == null ? "unmapped" : route,
                    response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            MDC.remove("requestId");
        }
    }
}
