package com.example.platform.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewaySecurityConfigTest {

    @Test
    void weakJwtSecretFailsFast() {
        GatewaySecurityConfig config = new GatewaySecurityConfig(new ObjectMapper());

        assertThatThrownBy(() -> config.jwtDecoder("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }
}
