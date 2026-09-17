package com.example.platform.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CorrelationIdRestClientCustomizerTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesCurrentCorrelationIdToDownstreamCalls() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://downstream");
        new PlatformObservabilityAutoConfiguration()
                .correlationIdRestClientCustomizer().customize(builder);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://downstream/test"))
                .andExpect(header(CorrelationIds.HEADER, "request-456"))
                .andRespond(withSuccess());
        MDC.put(CorrelationIds.MDC_KEY, "request-456");

        builder.build().get().uri("/test").retrieve().toBodilessEntity();

        server.verify();
    }
}
