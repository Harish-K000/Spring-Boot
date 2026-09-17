package com.example.platform.gateway;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.cloud.gateway.config.HttpClientProperties;

import java.time.Duration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "security.jwt.secret=gateway-test-secret-with-at-least-32-bytes")
@AutoConfigureObservability
class EdgeGatewayApplicationTests {
	@Autowired PrometheusMeterRegistry prometheus;
	@Autowired ApplicationContext context;
	@Autowired HttpClientProperties httpClientProperties;

	@Test
	void contextLoads() {
		org.assertj.core.api.Assertions.assertThat(prometheus).isNotNull();
		org.assertj.core.api.Assertions.assertThat(context)
				.isInstanceOf(ReactiveWebServerApplicationContext.class);
	}

	@Test
	void bindsBoundedDownstreamHttpTimeouts() {
		org.assertj.core.api.Assertions.assertThat(httpClientProperties.getConnectTimeout())
				.isEqualTo(2_000);
		org.assertj.core.api.Assertions.assertThat(httpClientProperties.getResponseTimeout())
				.isEqualTo(Duration.ofSeconds(10));
	}

}
