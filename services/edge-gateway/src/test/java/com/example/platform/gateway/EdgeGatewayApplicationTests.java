package com.example.platform.gateway;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

@SpringBootTest(properties = "security.jwt.secret=gateway-test-secret-with-at-least-32-bytes")
@AutoConfigureObservability
class EdgeGatewayApplicationTests {
	@Autowired PrometheusMeterRegistry prometheus;

	@Test
	void contextLoads() {
		org.assertj.core.api.Assertions.assertThat(prometheus).isNotNull();
	}

}
