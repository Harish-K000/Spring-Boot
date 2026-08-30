package com.example.platform.auth.mapper;

import com.example.platform.auth.dto.UserResponse;
import com.example.platform.auth.model.User;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** Entity to response DTO conversion. Keeps entities out of the HTTP layer. */
@Component
public class UserMapper {

    public UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                splitRoles(user.getRoles()),
                user.isEnabled(),
                user.getCreatedAt());
    }

    public static List<String> splitRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return List.of();
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .toList();
    }
}
