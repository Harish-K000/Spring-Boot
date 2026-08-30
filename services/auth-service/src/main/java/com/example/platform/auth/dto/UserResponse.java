package com.example.platform.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        List<String> roles,
        boolean enabled,
        Instant createdAt
) {}
