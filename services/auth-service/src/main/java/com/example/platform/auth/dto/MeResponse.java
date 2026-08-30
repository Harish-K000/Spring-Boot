package com.example.platform.auth.dto;

import java.util.List;

public record MeResponse(
        String userId,
        List<String> roles
) {}
