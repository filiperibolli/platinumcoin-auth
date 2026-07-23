package com.platinumcoin.auth.domain.error;

public class IdentityProviderUnavailableException extends RuntimeException {

    public IdentityProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
