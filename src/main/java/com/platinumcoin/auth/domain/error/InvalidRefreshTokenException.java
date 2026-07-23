package com.platinumcoin.auth.domain.error;

public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Refresh token inválido");
    }
}
