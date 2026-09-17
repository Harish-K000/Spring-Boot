package com.example.platform.gateway.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

class GatewayRequestLoggingFilterTest {

    @Test
    void logsBoundedRequestMetadataWithoutHeadersQueryOrBody() {
        GatewayRequestLoggingFilter filter = new GatewayRequestLoggingFilter("edge-gateway");
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/v1/orders?refresh_token=secret-query")
                .header("Authorization", "Bearer secret-token")
                .body("{\"password\":\"secret-body\"}"));
        exchange.getAttributes().put(RequestCorrelationFilter.CORRELATION_ID_ATTRIBUTE, "request-123");
        exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, Route.async()
                .id("orders-service")
                .uri(URI.create("http://localhost:8082"))
                .predicate(ignored -> true)
                .build());

        Logger logger = (Logger) LoggerFactory.getLogger(GatewayRequestLoggingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            StepVerifier.create(filter.filter(exchange, current -> {
                current.getResponse().setStatusCode(HttpStatus.CREATED);
                return Mono.empty();
            })).verifyComplete();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        Map<String, Object> values = event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));

        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(values)
                .containsEntry("event", "http_request_completed")
                .containsEntry("service", "edge-gateway")
                .containsEntry("correlationId", "request-123")
                .containsEntry("httpMethod", "POST")
                .containsEntry("httpPath", "/api/v1/orders")
                .containsEntry("httpStatus", 201)
                .containsEntry("routeId", "orders-service")
                .containsKey("durationMs");
        assertThat(event.toString())
                .doesNotContain("secret-token", "secret-query", "secret-body", "Authorization");
    }
}
