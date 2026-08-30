package com.example.platform.auth.exception;

/**
 * Wrong email or password. Deliberately carries a single opaque message so the response
 * cannot be used to probe which emails are registered.
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
