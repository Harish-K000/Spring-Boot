package com.example.platform.gateway.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayCorsConfigTest {

    private final GatewayCorsConfig config = new GatewayCorsConfig();

    @Test
    void rejectsWildcardOrigins() {
        GatewayCorsProperties properties = new GatewayCorsProperties(List.of("*"));

        assertThatThrownBy(() -> config.gatewayCorsConfigurationSource(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit HTTP(S) origins");
    }

    @Test
    void rejectsOriginsContainingPaths() {
        GatewayCorsProperties properties = new GatewayCorsProperties(
                List.of("https://example.com/application"));

        assertThatThrownBy(() -> config.gatewayCorsConfigurationSource(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid CORS origin");
    }
}
