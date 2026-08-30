package com.example.platform.auth.controller;

import com.example.platform.auth.dto.MeResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/protected")
public class ProtectedController {

    /**
     * Requires authentication via the security filter chain, so {@code auth} is never null here;
     * an anonymous request is rejected with 401 before reaching this method.
     */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(Authentication auth) {
        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        return ResponseEntity.ok(new MeResponse(auth.getName(), roles));
    }
}
