package com.example.platform.payments;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

@SpringBootTest
@AutoConfigureObservability
class PaymentsServiceApplicationTests {
	@Autowired PrometheusMeterRegistry prometheus;

	@Test
	void contextLoads() {
		org.assertj.core.api.Assertions.assertThat(prometheus).isNotNull();
	}

}
