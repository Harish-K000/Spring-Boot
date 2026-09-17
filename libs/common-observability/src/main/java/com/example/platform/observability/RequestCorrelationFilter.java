package com.example.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Establishes one safe correlation ID for the response, downstream calls, and logs. */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = CorrelationIds.HEADER;
    public static final String CORRELATION_ID_MDC_KEY = CorrelationIds.MDC_KEY;
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = CorrelationIds.resolve(
                request.getHeader(CORRELATION_ID_HEADER),
                request.getHeader(CorrelationIds.LEGACY_HEADER));
        String previous = MDC.get(CORRELATION_ID_MDC_KEY);
        long started = System.nanoTime();
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        request.setAttribute(CORRELATION_ID_MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.atInfo()
                    .addKeyValue("httpMethod", request.getMethod())
                    .addKeyValue("httpPath", request.getRequestURI())
                    .addKeyValue("httpStatus", response.getStatus())
                    .addKeyValue("durationMs", durationMs)
                    .log("HTTP request completed");
            if (previous == null) {
                MDC.remove(CORRELATION_ID_MDC_KEY);
            } else {
                MDC.put(CORRELATION_ID_MDC_KEY, previous);
            }
        }
    }
}
