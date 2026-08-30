package com.example.platform.auth.exception;

/** The account exists and the password matched, but the account is disabled. Maps to 403 Forbidden. */
public class AccountDisabledException extends RuntimeException {
    public AccountDisabledException() {
        super("This account is disabled");
    }
}
