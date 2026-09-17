package com.example.platform.gateway.error;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.cloud.client.circuitbreaker.NoFallbackAvailableException;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.ServiceUnavailableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/** Converts gateway/framework failures to the same safe public JSON contract. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayErrorWebExceptionHandler implements WebExceptionHandler {

    private final GatewayErrorResponseWriter writer;

    public GatewayErrorWebExceptionHandler(GatewayErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(failure);
        }
        ErrorDescriptor error = classify(failure);
        return writer.write(exchange, error.status(), error.code(), error.message());
    }

    private ErrorDescriptor classify(Throwable failure) {
        if (hasCause(failure, TimeoutException.class)
                || hasCause(failure, org.springframework.cloud.gateway.support.TimeoutException.class)) {
            return new ErrorDescriptor(HttpStatus.GATEWAY_TIMEOUT, "GATEWAY_TIMEOUT",
                    "The requested service did not respond in time.");
        }
        if (hasCause(failure, ConnectException.class)
                || hasCause(failure, NotFoundException.class)
                || hasCause(failure, ServiceUnavailableException.class)
                || hasCause(failure, CallNotPermittedException.class)) {
            return new ErrorDescriptor(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "The requested service is temporarily unavailable.");
        }
        if (hasCause(failure, NoFallbackAvailableException.class)) {
            return new ErrorDescriptor(HttpStatus.BAD_GATEWAY, "DOWNSTREAM_FAILURE",
                    "The requested service could not complete the request.");
        }
        if (failure instanceof ResponseStatusException statusException) {
            return forStatus(HttpStatus.resolve(statusException.getStatusCode().value()));
        }
        return new ErrorDescriptor(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_GATEWAY_ERROR",
                "The gateway could not process the request.");
    }

    private ErrorDescriptor forStatus(HttpStatus status) {
        if (status == null) {
            return new ErrorDescriptor(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_GATEWAY_ERROR",
                    "The gateway could not process the request.");
        }
        return switch (status) {
            case BAD_REQUEST -> new ErrorDescriptor(status, "INVALID_REQUEST",
                    "The request is invalid.");
            case NOT_FOUND -> new ErrorDescriptor(status, "ROUTE_NOT_FOUND",
                    "No gateway route matches the request.");
            case METHOD_NOT_ALLOWED -> new ErrorDescriptor(status, "METHOD_NOT_ALLOWED",
                    "The HTTP method is not allowed for this resource.");
            case TOO_MANY_REQUESTS -> new ErrorDescriptor(status, "RATE_LIMIT_EXCEEDED",
                    "Too many requests. Try again later.");
            case BAD_GATEWAY -> new ErrorDescriptor(status, "DOWNSTREAM_FAILURE",
                    "The requested service could not complete the request.");
            case SERVICE_UNAVAILABLE -> new ErrorDescriptor(status, "SERVICE_UNAVAILABLE",
                    "The requested service is temporarily unavailable.");
            case GATEWAY_TIMEOUT -> new ErrorDescriptor(status, "GATEWAY_TIMEOUT",
                    "The requested service did not respond in time.");
            default -> status.is4xxClientError()
                    ? new ErrorDescriptor(status, "REQUEST_REJECTED", "The request was rejected.")
                    : new ErrorDescriptor(HttpStatus.INTERNAL_SERVER_ERROR,
                            "INTERNAL_GATEWAY_ERROR", "The gateway could not process the request.");
        };
    }

    private boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record ErrorDescriptor(HttpStatus status, String code, String message) {
    }
}
