package com.example.platform.gateway;

import com.example.platform.observability.CorrelationIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "security.jwt.secret=gateway-test-secret-with-at-least-32-bytes")
@AutoConfigureWebTestClient
class GatewayHealthIntegrationTest {

    @Autowired private WebTestClient client;
    @Autowired private ConfigurableApplicationContext context;

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/livez",
            "/readyz"
    })
    void publicHealthEndpointsReturnSanitizedUpResponse(String path) {
        client.get().uri(path)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/vnd.spring-boot.actuator.v3+json")
                .expectHeader().exists(CorrelationIds.HEADER)
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist()
                .jsonPath("$.details").doesNotExist();
    }

    @Test
    void readinessReturns503WhileGatewayRefusesTraffic() {
        try {
            AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);

            client.get().uri("/readyz")
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().exists(CorrelationIds.HEADER)
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("OUT_OF_SERVICE")
                    .jsonPath("$.components").doesNotExist()
                    .jsonPath("$.details").doesNotExist();
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }
}
