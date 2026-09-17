package com.example.platform.gateway.error;

import com.example.platform.gateway.filter.RequestCorrelationFilter;
import com.example.platform.observability.CorrelationIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;

/** Serializes gateway-owned errors without exposing internal exception details. */
@Component
public class GatewayErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public GatewayErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status,
                            String code, String message) {
        String correlationId = exchange.getAttribute(
                RequestCorrelationFilter.CORRELATION_ID_ATTRIBUTE);
        if (correlationId == null) {
            correlationId = CorrelationIds.resolve(
                    exchange.getRequest().getHeaders().getFirst(CorrelationIds.HEADER),
                    exchange.getRequest().getHeaders().getFirst(CorrelationIds.LEGACY_HEADER));
            exchange.getAttributes().put(
                    RequestCorrelationFilter.CORRELATION_ID_ATTRIBUTE, correlationId);
        }

        GatewayErrorResponse error = new GatewayErrorResponse(
                Instant.now(), status.value(), code, message,
                exchange.getRequest().getPath().value(), correlationId);
        final byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(error);
        } catch (Exception ex) {
            return Mono.error(ex);
        }

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().set(CorrelationIds.HEADER, correlationId);
        return exchange.getResponse().writeWith(Mono.just(
                exchange.getResponse().bufferFactory().wrap(body)));
    }
}
