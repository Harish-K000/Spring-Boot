package com.example.platform.auth.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Issues and verifies the service's compact HS256 access tokens. */
@Component
public class JwtUtil {

    static final int MINIMUM_SECRET_BYTES = 32;
    public static final String TOKEN_ISSUER = "backend-platform-auth";
    public static final String TOKEN_AUDIENCE = "backend-platform-api";
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};

    private final byte[] secret;
    private final ObjectMapper mapper;
    private final SecureRandom random;

    public JwtUtil(@Value("${security.jwt.secret:}") String secret, ObjectMapper mapper) {
        byte[] candidate = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (candidate.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "security.jwt.secret (JWT_SECRET) must contain at least 32 UTF-8 bytes");
        }
        this.secret = candidate.clone();
        this.mapper = mapper;
        this.random = new SecureRandom();
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decodeCanonicalBase64Url(String encoded) {
        byte[] decoded = Base64.getUrlDecoder().decode(encoded);
        if (!base64UrlEncode(decoded).equals(encoded)) {
            throw new IllegalArgumentException("Non-canonical Base64URL value");
        }
        return decoded;
    }

    private byte[] hmacSha256(byte[] message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(message);
    }

    public String generateToken(String userId, String email, String roles, long expiresSeconds) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");

            long issuedAt = Instant.now().getEpochSecond();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sub", userId);
            payload.put("email", email);
            payload.put("roles", roles);
            payload.put("iss", TOKEN_ISSUER);
            payload.put("aud", TOKEN_AUDIENCE);
            payload.put("iat", issuedAt);
            payload.put("exp", issuedAt + expiresSeconds);

            String signingInput = base64UrlEncode(mapper.writeValueAsBytes(header)) + "."
                    + base64UrlEncode(mapper.writeValueAsBytes(payload));
            String signature = base64UrlEncode(hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + signature;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not issue access token", ex);
        }
    }

    public Map<String, Object> validateAndGetClaims(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new IllegalArgumentException("Malformed token");
            }

            Map<String, Object> header = mapper.readValue(decodeCanonicalBase64Url(parts[0]), MAP_TYPE);
            if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))) {
                throw new IllegalArgumentException("Unsupported token header");
            }

            byte[] suppliedSignature = decodeCanonicalBase64Url(parts[2]);
            byte[] expectedSignature = hmacSha256((parts[0] + "." + parts[1])
                    .getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expectedSignature, suppliedSignature)) {
                throw new IllegalArgumentException("Invalid signature");
            }

            Map<String, Object> claims = mapper.readValue(decodeCanonicalBase64Url(parts[1]), MAP_TYPE);
            Object subject = claims.get("sub");
            Object expiration = claims.get("exp");
            if (!(subject instanceof String) || ((String) subject).isBlank()) {
                throw new IllegalArgumentException("Missing subject");
            }
            if (!(expiration instanceof Number)) {
                throw new IllegalArgumentException("Missing expiration");
            }
            if (!TOKEN_ISSUER.equals(claims.get("iss"))
                    || !TOKEN_AUDIENCE.equals(claims.get("aud"))) {
                throw new IllegalArgumentException("Invalid token issuer or audience");
            }
            Number expirationValue = (Number) expiration;
            if (Instant.now().getEpochSecond() >= expirationValue.longValue()) {
                throw new IllegalArgumentException("Token expired");
            }
            return claims;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid access token", ex);
        }
    }

    public String generateSecureRandomToken() {
        byte[] bytes = new byte[64];
        random.nextBytes(bytes);
        return base64UrlEncode(bytes);
    }
}
