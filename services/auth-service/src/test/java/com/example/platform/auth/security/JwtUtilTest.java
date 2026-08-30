package com.example.platform.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private final JwtUtil jwtUtil = new JwtUtil(
            "unit-test-secret-with-at-least-32-bytes", new ObjectMapper());

    @Test
    void issuedTokenRoundTripsExpectedClaims() {
        String token = jwtUtil.generateToken("user-123", "user@example.com", "USER,ADMIN", 60);

        Map<String, Object> claims = jwtUtil.validateAndGetClaims(token);

        assertThat(claims).containsEntry("sub", "user-123")
                .containsEntry("email", "user@example.com")
                .containsEntry("roles", "USER,ADMIN")
                .containsEntry("iss", JwtUtil.TOKEN_ISSUER)
                .containsEntry("aud", JwtUtil.TOKEN_AUDIENCE);
    }

    @Test
    void rejectsTamperedSignature() {
        String token = jwtUtil.generateToken("user-123", "user@example.com", "USER", 60);
        char replacement = token.charAt(token.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, token.length() - 1) + replacement;

        assertThatThrownBy(() -> jwtUtil.validateAndGetClaims(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid access token");
    }

    @Test
    void rejectsExpiredToken() {
        String token = jwtUtil.generateToken("user-123", "user@example.com", "USER", -1);

        assertThatThrownBy(() -> jwtUtil.validateAndGetClaims(token))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesWeakSigningSecret() {
        assertThatThrownBy(() -> new JwtUtil("too-short", new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void refreshTokenIsHighEntropyAndUnique() {
        String first = jwtUtil.generateSecureRandomToken();
        String second = jwtUtil.generateSecureRandomToken();

        assertThat(first).hasSizeGreaterThanOrEqualTo(80).isNotEqualTo(second);
    }
}
