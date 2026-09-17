package com.example.platform.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Shared Auth Service token contract for servlet resource servers. */
public final class PlatformJwtSecurity {

    public static final String TOKEN_ISSUER = "backend-platform-auth";
    public static final String TOKEN_AUDIENCE = "backend-platform-api";
    private static final int MINIMUM_SECRET_BYTES = 32;

    private PlatformJwtSecurity() {
    }

    /**
     * Builds an HS256 decoder that applies the same signature and claim checks in every service.
     */
    public static JwtDecoder jwtDecoder(String configuredSecret) {
        byte[] secretBytes = configuredSecret == null
                ? new byte[0]
                : configuredSecret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "security.jwt.secret (JWT_SECRET) must contain at least 32 UTF-8 bytes");
        }

        SecretKey key = new SecretKeySpec(secretBytes, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        OAuth2TokenValidator<Jwt> issuerAndTime =
                JwtValidators.createDefaultWithIssuer(TOKEN_ISSUER);
        OAuth2TokenValidator<Jwt> expiration = requiredClaim(
                jwt -> jwt.getExpiresAt() != null, "Expiration is missing");
        OAuth2TokenValidator<Jwt> audience = requiredClaim(
                jwt -> jwt.getAudience().contains(TOKEN_AUDIENCE),
                "Required audience is missing");
        OAuth2TokenValidator<Jwt> subject = requiredClaim(jwt -> {
            try {
                UUID.fromString(jwt.getSubject());
                return true;
            } catch (IllegalArgumentException | NullPointerException exception) {
                return false;
            }
        }, "Subject must be a UUID");

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerAndTime, expiration, audience, subject));
        return decoder;
    }

    /** Maps the Auth Service's comma-separated roles claim to ROLE_* authorities. */
    public static Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthoritiesClaimDelimiter("\\s*,\\s*");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * Forwards only the already-validated bearer token from Spring Security to a downstream
     * service. This keeps service-to-service calls inside the same authenticated request chain.
     */
    public static ClientHttpRequestInterceptor bearerTokenForwardingInterceptor() {
        return new ValidatedBearerTokenForwardingInterceptor();
    }

    private static OAuth2TokenValidator<Jwt> requiredClaim(
            java.util.function.Predicate<Jwt> predicate, String description) {
        return jwt -> predicate.test(jwt)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", description, null));
    }

    private static final class ValidatedBearerTokenForwardingInterceptor
            implements ClientHttpRequestInterceptor {

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                            ClientHttpRequestExecution execution)
                throws IOException {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwtAuthentication
                    && authentication.isAuthenticated()) {
                request.getHeaders().set(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + jwtAuthentication.getToken().getTokenValue());
            }
            return execution.execute(request, body);
        }
    }
}
