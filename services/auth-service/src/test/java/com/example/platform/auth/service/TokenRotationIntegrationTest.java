package com.example.platform.auth.service;

import com.example.platform.auth.dto.AuthResponse;
import com.example.platform.auth.exception.AccountDisabledException;
import com.example.platform.auth.exception.InvalidRefreshTokenException;
import com.example.platform.auth.model.RefreshToken;
import com.example.platform.auth.model.User;
import com.example.platform.auth.repository.RefreshTokenRepository;
import com.example.platform.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class TokenRotationIntegrationTest {

    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;

    @BeforeEach
    void cleanDatabase() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void replayRevocationCommitsEvenThoughRefreshReturnsAnError() {
        User user = persistUser(true);
        UUID familyId = UUID.randomUUID();
        persistToken(user, familyId, "already-used", Instant.now().minus(1, ChronoUnit.MINUTES));
        RefreshToken liveSibling = persistToken(user, familyId, "live-sibling", null);

        assertThatThrownBy(() -> authService.refresh("already-used"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(refreshTokenRepository.findById(liveSibling.getId())).get()
                .matches(RefreshToken::isRevoked, "live token in replayed family is committed as revoked");
    }

    @Test
    void disabledAccountRefreshRevokesFamilyAndCommits() {
        User user = persistUser(false);
        UUID familyId = UUID.randomUUID();
        RefreshToken token = persistToken(user, familyId, "disabled-user-token", null);

        assertThatThrownBy(() -> authService.refresh("disabled-user-token"))
                .isInstanceOf(AccountDisabledException.class);

        assertThat(refreshTokenRepository.findById(token.getId())).get()
                .matches(RefreshToken::isRevoked);
    }

    @Test
    void twoConcurrentRefreshesCannotBothRotateTheSameToken() throws Exception {
        User user = persistUser(true);
        UUID familyId = UUID.randomUUID();
        persistToken(user, familyId, "one-use-token", null);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> refreshAfterSignal(ready, start));
            Future<Object> second = executor.submit(() -> refreshAfterSignal(ready, start));
            ready.await();
            start.countDown();

            List<Object> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes).filteredOn(AuthResponse.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(InvalidRefreshTokenException.class::isInstance).hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(refreshTokenRepository.findAllByFamilyId(familyId))
                .isNotEmpty()
                .allMatch(RefreshToken::isRevoked);
    }

    private Object refreshAfterSignal(CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return authService.refresh("one-use-token");
        } catch (InvalidRefreshTokenException ex) {
            return ex;
        }
    }

    private User persistUser(boolean enabled) {
        User user = new User();
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash("hashed");
        user.setEnabled(enabled);
        return userRepository.saveAndFlush(user);
    }

    private RefreshToken persistToken(User user, UUID familyId, String raw, Instant revokedAt) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setFamilyId(familyId);
        token.setTokenHash(sha256Hex(raw));
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
        token.setRevokedAt(revokedAt);
        return refreshTokenRepository.saveAndFlush(token);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
