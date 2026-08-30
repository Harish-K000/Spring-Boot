package com.example.platform.auth.service;

import com.example.platform.auth.dto.AuthResponse;
import com.example.platform.auth.dto.LoginRequest;
import com.example.platform.auth.dto.RegisterRequest;
import com.example.platform.auth.dto.UserResponse;
import com.example.platform.auth.exception.AccountDisabledException;
import com.example.platform.auth.exception.EmailAlreadyInUseException;
import com.example.platform.auth.exception.InvalidCredentialsException;
import com.example.platform.auth.exception.InvalidRefreshTokenException;
import com.example.platform.auth.mapper.UserMapper;
import com.example.platform.auth.model.RefreshToken;
import com.example.platform.auth.model.User;
import com.example.platform.auth.repository.RefreshTokenRepository;
import com.example.platform.auth.repository.UserRepository;
import com.example.platform.auth.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository, refreshTokenRepository, passwordEncoder, jwtUtil, new UserMapper());
    }

    private User enabledUser() {
        User user = new User();
        user.setEmail("user@example.com");
        user.setPasswordHash("hashed");
        user.setRoles("USER");
        user.setEnabled(true);
        return user;
    }

    @Test
    void registerHashesPasswordAndReturnsUser() {
        given(userRepository.findByEmail("user@example.com")).willReturn(Optional.empty());
        given(passwordEncoder.encode("correct-horse")).willReturn("hashed");
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        UserResponse response = authService.register(new RegisterRequest("user@example.com", "correct-horse"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("hashed");
        assertThat(saved.getValue().getRoles()).isEqualTo("USER");
        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.roles()).containsExactly("USER");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        given(userRepository.findByEmail("user@example.com")).willReturn(Optional.of(enabledUser()));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("user@example.com", "correct-horse")))
                .isInstanceOf(EmailAlreadyInUseException.class);

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerNormalizesEmailBeforeCheckingAndSaving() {
        given(userRepository.findByEmail("user@example.com")).willReturn(Optional.empty());
        given(passwordEncoder.encode("correct-horse")).willReturn("hashed");
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(inv -> inv.getArgument(0));

        UserResponse response = authService.register(
                new RegisterRequest("  User@Example.COM ", "correct-horse"));

        assertThat(response.email()).isEqualTo("user@example.com");
    }

    @Test
    void loginIssuesTokensAndStartsNewFamily() {
        User user = enabledUser();
        given(userRepository.findByEmail("user@example.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("correct-horse", "hashed")).willReturn(true);
        given(jwtUtil.generateToken(anyString(), anyString(), anyString(), anyLong())).willReturn("access-token");
        given(jwtUtil.generateSecureRandomToken()).willReturn("raw-refresh");
        given(refreshTokenRepository.save(any(RefreshToken.class))).willAnswer(inv -> inv.getArgument(0));

        AuthResponse response = authService.login(new LoginRequest("user@example.com", "correct-horse"));

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("raw-refresh");
        assertThat(response.tokenType()).isEqualTo("Bearer");

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        // The raw token must never be persisted; only its SHA-256 hash is stored.
        assertThat(saved.getValue().getTokenHash()).isNotEqualTo("raw-refresh").hasSize(64);
        assertThat(saved.getValue().getFamilyId()).isNotNull();
        assertThat(saved.getValue().getExpiresAt()).isAfter(Instant.now().plus(29, ChronoUnit.DAYS));
    }

    @Test
    void loginWithUnknownEmailThrowsInvalidCredentials() {
        given(userRepository.findByEmail("nobody@example.com")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "whatever1")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void loginWithWrongPasswordThrowsInvalidCredentials() {
        given(userRepository.findByEmail("user@example.com")).willReturn(Optional.of(enabledUser()));
        given(passwordEncoder.matches("wrong-password", "hashed")).willReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginOnDisabledAccountThrowsAccountDisabled() {
        User user = enabledUser();
        user.setEnabled(false);
        given(userRepository.findByEmail("user@example.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("correct-horse", "hashed")).willReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "correct-horse")))
                .isInstanceOf(AccountDisabledException.class);
    }

    @Test
    void refreshRotatesTokenWithinSameFamily() {
        User user = enabledUser();
        UUID familyId = UUID.randomUUID();
        RefreshToken current = new RefreshToken();
        current.setUser(user);
        current.setFamilyId(familyId);
        current.setTokenHash("stored-hash");
        current.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));

        given(refreshTokenRepository.findByTokenHashForUpdate(anyString())).willReturn(Optional.of(current));
        given(jwtUtil.generateSecureRandomToken()).willReturn("new-raw-refresh");
        given(jwtUtil.generateToken(anyString(), anyString(), anyString(), anyLong())).willReturn("access-token");
        given(refreshTokenRepository.save(any(RefreshToken.class))).willAnswer(inv -> inv.getArgument(0));

        AuthResponse response = authService.refresh("old-raw-refresh");

        assertThat(response.refreshToken()).isEqualTo("new-raw-refresh");
        assertThat(current.isRevoked()).isTrue();
        assertThat(current.getReplacedBy()).isNotNull();

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getFamilyId()).isEqualTo(familyId);
    }

    @Test
    void refreshWithUnknownTokenThrows() {
        given(refreshTokenRepository.findByTokenHashForUpdate(anyString())).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("nope"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage("Invalid refresh token");
    }

    @Test
    void refreshWithReusedTokenRevokesWholeFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshToken reused = new RefreshToken();
        reused.setUser(enabledUser());
        reused.setFamilyId(familyId);
        reused.setRevokedAt(Instant.now().minus(1, ChronoUnit.HOURS));
        reused.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
        given(refreshTokenRepository.findByTokenHashForUpdate(anyString())).willReturn(Optional.of(reused));

        assertThatThrownBy(() -> authService.refresh("replayed"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage("Refresh token has already been used");

        verify(refreshTokenRepository).revokeFamily(eq(familyId), any(Instant.class));
    }

    @Test
    void refreshWithExpiredTokenThrows() {
        RefreshToken expired = new RefreshToken();
        expired.setUser(enabledUser());
        expired.setFamilyId(UUID.randomUUID());
        expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        given(refreshTokenRepository.findByTokenHashForUpdate(anyString())).willReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh("stale"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .hasMessage("Refresh token has expired");
    }

    @Test
    void logoutRevokesFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshToken token = new RefreshToken();
        token.setFamilyId(familyId);
        given(refreshTokenRepository.findByTokenHashForUpdate(anyString())).willReturn(Optional.of(token));

        authService.logout("some-token");

        verify(refreshTokenRepository).revokeFamily(eq(familyId), any(Instant.class));
    }

    @Test
    void logoutWithUnknownTokenIsANoOp() {
        given(refreshTokenRepository.findByTokenHashForUpdate(anyString())).willReturn(Optional.empty());

        authService.logout("unknown");

        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    void refreshDisabledAccountRevokesWholeFamily() {
        UUID familyId = UUID.randomUUID();
        User user = enabledUser();
        user.setEnabled(false);
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setFamilyId(familyId);
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS));
        given(refreshTokenRepository.findByTokenHashForUpdate(anyString()))
                .willReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.refresh("disabled-user-token"))
                .isInstanceOf(AccountDisabledException.class);

        verify(refreshTokenRepository).revokeFamily(eq(familyId), any(Instant.class));
        verify(refreshTokenRepository, never()).save(any());
    }
}
