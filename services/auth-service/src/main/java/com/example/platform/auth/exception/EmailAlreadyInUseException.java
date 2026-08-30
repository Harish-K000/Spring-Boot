package com.example.platform.auth.exception;

/** Registration attempted with an email that already has an account. Maps to 409 Conflict. */
public class EmailAlreadyInUseException extends RuntimeException {
    public EmailAlreadyInUseException(String email) {
        super("An account already exists for " + email);
    }
}
