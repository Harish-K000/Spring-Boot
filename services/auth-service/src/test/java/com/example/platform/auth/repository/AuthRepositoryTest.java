package com.example.platform.auth.repository;

import com.example.platform.auth.model.RefreshToken;
import com.example.platform.auth.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Persistence layer against in-memory H2: verifies the derived queries and the bulk revoke. */
@DataJpaTest
class AuthRepositoryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;

    private User persistUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("hashed");
        return userRepository.saveAndFlush(user);
    }

    private RefreshToken persistToken(User user, UUID familyId, String hash) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setFamilyId(familyId);
        token.setTokenHash(hash);
        token.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        return refreshTokenRepository.saveAndFlush(token);
    }

    @Test
    void findByEmailReturnsPersistedUser() {
        persistUser("user@example.com");

        Optional<User> found = userRepository.findByEmail("user@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getRoles()).isEqualTo("USER");
        assertThat(found.get().isEnabled()).isTrue();
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void findByEmailReturnsEmptyForUnknownAddress() {
        assertThat(userRepository.findByEmail("nobody@example.com")).isEmpty();
    }

    @Test
    void emailUniquenessIsEnforced() {
        persistUser("duplicate@example.com");

        assertThatThrownBy(() -> persistUser("duplicate@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existsByEmailReflectsPersistedState() {
        persistUser("present@example.com");

        assertThat(userRepository.existsByEmail("present@example.com")).isTrue();
        assertThat(userRepository.existsByEmail("absent@example.com")).isFalse();
    }

    @Test
    void findByTokenHashLocatesToken() {
        User user = persistUser("token-owner@example.com");
        persistToken(user, UUID.randomUUID(), "hash-a");

        assertThat(refreshTokenRepository.findByTokenHash("hash-a")).isPresent();
        assertThat(refreshTokenRepository.findByTokenHash("missing")).isEmpty();
    }

    @Test
    void revokeFamilyRevokesOnlyLiveTokensInThatFamily() {
        User user = persistUser("family@example.com");
        UUID family = UUID.randomUUID();
        UUID otherFamily = UUID.randomUUID();

        persistToken(user, family, "hash-1");
        persistToken(user, family, "hash-2");
        persistToken(user, otherFamily, "hash-3");

        int revoked = refreshTokenRepository.revokeFamily(family, Instant.now());

        assertThat(revoked).isEqualTo(2);
        List<RefreshToken> inFamily = refreshTokenRepository.findAllByFamilyId(family);
        assertThat(inFamily).allMatch(RefreshToken::isRevoked);
        assertThat(refreshTokenRepository.findByTokenHash("hash-3")).get()
                .matches(t -> !t.isRevoked(), "token in another family stays live");
    }

    @Test
    void revokeFamilyIsIdempotent() {
        User user = persistUser("idempotent@example.com");
        UUID family = UUID.randomUUID();
        persistToken(user, family, "hash-x");

        assertThat(refreshTokenRepository.revokeFamily(family, Instant.now())).isEqualTo(1);
        // Already revoked, so the second call matches nothing and leaves revokedAt untouched.
        assertThat(refreshTokenRepository.revokeFamily(family, Instant.now())).isZero();
    }
}
