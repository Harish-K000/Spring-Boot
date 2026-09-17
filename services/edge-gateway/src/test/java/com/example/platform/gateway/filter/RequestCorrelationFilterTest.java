package com.example.platform.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void preservesSafeCorrelationIdInDownstreamRequestAndResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/orders")
                .header(RequestCorrelationFilter.CORRELATION_ID_HEADER, "request-123")
                .build());
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, capture(captured))).verifyComplete();

        assertThat(captured.get().getRequest().getHeaders()
                .getFirst(RequestCorrelationFilter.CORRELATION_ID_HEADER)).isEqualTo("request-123");
        assertThat(exchange.getResponse().getHeaders()
                .getFirst(RequestCorrelationFilter.CORRELATION_ID_HEADER)).isEqualTo("request-123");
    }

    @Test
    void replacesUnsafeCorrelationId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/v1/orders")
                .header(RequestCorrelationFilter.CORRELATION_ID_HEADER, "unsafe value with spaces")
                .build());
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, capture(captured))).verifyComplete();

        String generated = captured.get().getRequest().getHeaders()
                .getFirst(RequestCorrelationFilter.CORRELATION_ID_HEADER);
        assertThat(generated).isNotBlank().isNotEqualTo("unsafe value with spaces");
        assertThat(exchange.getResponse().getHeaders()
                .getFirst(RequestCorrelationFilter.CORRELATION_ID_HEADER)).isEqualTo(generated);
    }

    private WebFilterChain capture(AtomicReference<ServerWebExchange> captured) {
        return filtered -> {
            captured.set(filtered);
            return Mono.empty();
        };
    }
}
