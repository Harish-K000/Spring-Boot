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
import io.micrometer.observation.annotation.Observed;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);
    private static final String DEFAULT_ROLES = "USER";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil,
                       UserMapper userMapper) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS; unreachable on any supported JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Transactional
    @Observed(name = "platform.auth.register", contextualName = "register-user")
    public UserResponse register(RegisterRequest req) {
        String email = normalizeEmail(req.email());
        userRepository.findByEmail(email).ifPresent(existing -> {
            throw new EmailAlreadyInUseException(email);
        });

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setRoles(DEFAULT_ROLES);
        user.setEnabled(true);

        try {
            // Flush here so a concurrent registration that wins the unique-key race is still
            // translated to the public 409 response rather than surfacing later as a 500.
            return userMapper.toResponse(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException ex) {
            throw new EmailAlreadyInUseException(email);
        }
    }

    @Transactional
    @Observed(name = "platform.auth.login", contextualName = "login-user")
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(normalizeEmail(req.email()))
                .orElseThrow(InvalidCredentialsException::new);

        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        if (!user.isEnabled()) {
            throw new AccountDisabledException();
        }

        // A fresh login starts a new refresh token family.
        IssuedToken issued = issueRefreshToken(user, UUID.randomUUID());
        return AuthResponse.bearer(accessTokenFor(user), issued.raw(), ACCESS_TOKEN_TTL.toSeconds());
    }

    // Reuse detection deliberately returns 401 after revoking the token family. That security
    // write must commit even though InvalidRefreshTokenException leaves the method.
    @Transactional(noRollbackFor = {InvalidRefreshTokenException.class, AccountDisabledException.class})
    @Observed(name = "platform.auth.refresh", contextualName = "refresh-session")
    public AuthResponse refresh(String refreshRaw) {
        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(sha256Hex(refreshRaw))
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token"));

        if (token.isRevoked()) {
            // Replay of an already-rotated token: assume the family is compromised and kill it.
            revokeFamily(token.getFamilyId());
            throw new InvalidRefreshTokenException("Refresh token has already been used");
        }
        if (token.isExpired()) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        User user = token.getUser();
        if (!user.isEnabled()) {
            revokeFamily(token.getFamilyId());
            throw new AccountDisabledException();
        }
        IssuedToken issued = issueRefreshToken(user, token.getFamilyId());

        token.setRevokedAt(Instant.now());
        token.setReplacedBy(issued.entity().getId());
        refreshTokenRepository.save(token);

        return AuthResponse.bearer(accessTokenFor(user), issued.raw(), ACCESS_TOKEN_TTL.toSeconds());
    }

    @Transactional
    @Observed(name = "platform.auth.logout", contextualName = "logout-session")
    public void logout(String refreshRaw) {
        // Logout is idempotent: an unknown token is simply a no-op.
        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHashForUpdate(sha256Hex(refreshRaw));
        found.ifPresent(token -> revokeFamily(token.getFamilyId()));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String accessTokenFor(User user) {
        return jwtUtil.generateToken(
                user.getId().toString(),
                user.getEmail(),
                user.getRoles(),
                ACCESS_TOKEN_TTL.toSeconds());
    }

    /** The stored token together with the raw value, which exists only in memory and is never persisted. */
    private static final class IssuedToken {
        private final RefreshToken entity;
        private final String raw;

        private IssuedToken(RefreshToken entity, String raw) {
            this.entity = entity;
            this.raw = raw;
        }

        private RefreshToken entity() {
            return entity;
        }

        private String raw() {
            return raw;
        }
    }

    /** Persists a new refresh token in {@code familyId} and returns the raw value to hand to the client. */
    private IssuedToken issueRefreshToken(User user, UUID familyId) {
        String raw = jwtUtil.generateSecureRandomToken();
        RefreshToken token = new RefreshToken();
        token.setTokenHash(sha256Hex(raw));
        token.setUser(user);
        token.setFamilyId(familyId);
        token.setExpiresAt(Instant.now().plus(REFRESH_TOKEN_TTL));
        return new IssuedToken(refreshTokenRepository.save(token), raw);
    }

    private void revokeFamily(UUID familyId) {
        refreshTokenRepository.revokeFamily(familyId, Instant.now());
    }
}
