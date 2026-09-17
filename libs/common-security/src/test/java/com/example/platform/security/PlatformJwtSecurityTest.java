package com.example.platform.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformJwtSecurityTest {

    private static final String SECRET = "service-test-secret-with-at-least-32-bytes";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsAuthServiceTokenContractAndMapsRoles() throws Exception {
        UUID subject = UUID.randomUUID();
        Jwt jwt = PlatformJwtSecurity.jwtDecoder(SECRET).decode(
                token(subject.toString(), PlatformJwtSecurity.TOKEN_AUDIENCE, true));

        assertThat(jwt.getSubject()).isEqualTo(subject.toString());
        assertThat(PlatformJwtSecurity.jwtAuthenticationConverter().convert(jwt)
                .getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void rejectsNonUuidSubject() throws Exception {
        assertThatThrownBy(() -> PlatformJwtSecurity.jwtDecoder(SECRET)
                .decode(token("spoofed-user", PlatformJwtSecurity.TOKEN_AUDIENCE, true)))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsMissingExpiration() throws Exception {
        assertThatThrownBy(() -> PlatformJwtSecurity.jwtDecoder(SECRET)
                .decode(token(UUID.randomUUID().toString(), PlatformJwtSecurity.TOKEN_AUDIENCE, false)))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        assertThatThrownBy(() -> PlatformJwtSecurity.jwtDecoder(SECRET)
                .decode(token(UUID.randomUUID().toString(), "another-api", true)))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void refusesWeakSharedSecretAtStartup() {
        assertThatThrownBy(() -> PlatformJwtSecurity.jwtDecoder("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void forwardsValidatedBearerTokenAndReplacesExistingAuthorization() throws Exception {
        Jwt jwt = Jwt.withTokenValue("validated-token")
                .header("alg", "HS256")
                .subject(UUID.randomUUID().toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, java.util.List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        var request = new TestHttpRequest();
        request.getHeaders().setBearerAuth("caller-supplied-token");

        PlatformJwtSecurity.bearerTokenForwardingInterceptor().intercept(
                request, new byte[0], (forwarded, body) -> {
                    assertThat(forwarded.getHeaders().getFirst("Authorization"))
                            .isEqualTo("Bearer validated-token");
                    return null;
                });
    }

    private String token(String subject, String audience, boolean includeExpiration) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(PlatformJwtSecurity.TOKEN_ISSUER)
                .audience(audience)
                .subject(subject)
                .issueTime(Date.from(now.minusSeconds(1)))
                .claim("roles", "USER,ADMIN");
        if (includeExpiration) {
            claims.expirationTime(Date.from(now.plusSeconds(300)));
        }

        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        token.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return token.serialize();
    }

    private static final class TestHttpRequest implements HttpRequest {
        private final org.springframework.http.HttpHeaders headers =
                new org.springframework.http.HttpHeaders();
        private final Map<String, Object> attributes = new HashMap<>();

        @Override
        public HttpMethod getMethod() {
            return HttpMethod.GET;
        }

        @Override
        public URI getURI() {
            return URI.create("http://downstream.test/resource");
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }
    }
}
