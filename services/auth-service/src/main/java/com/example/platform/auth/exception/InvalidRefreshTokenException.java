package com.example.platform.auth.exception;

/** Refresh token is unknown, expired, or was already rotated. Maps to 401 Unauthorized. */
public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
