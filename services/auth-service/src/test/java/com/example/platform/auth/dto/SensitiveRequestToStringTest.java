package com.example.platform.auth.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveRequestToStringTest {

    @Test
    void requestRepresentationsDoNotExposeCredentialsOrTokens() {
        String password = "correct-horse-battery-staple";
        String refreshToken = "raw-refresh-token";

        assertThat(new LoginRequest("user@example.com", password).toString())
                .contains("user@example.com", "[REDACTED]")
                .doesNotContain(password);
        assertThat(new RegisterRequest("user@example.com", password).toString())
                .contains("user@example.com", "[REDACTED]")
                .doesNotContain(password);
        assertThat(new RefreshRequest(refreshToken).toString())
                .contains("[REDACTED]")
                .doesNotContain(refreshToken);
    }

    @Test
    void authenticationResponseRepresentationDoesNotExposeIssuedTokens() {
        String accessToken = "signed-access-token";
        String refreshToken = "raw-refresh-token";

        assertThat(AuthResponse.bearer(accessToken, refreshToken, 900).toString())
                .contains("accessToken=[REDACTED]", "refreshToken=[REDACTED]", "expiresIn=900")
                .doesNotContain(accessToken, refreshToken);
    }
}
