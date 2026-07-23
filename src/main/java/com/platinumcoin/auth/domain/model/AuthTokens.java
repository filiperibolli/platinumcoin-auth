package com.platinumcoin.auth.domain.model;

public record AuthTokens(
        String accessToken,
        String refreshToken,
        long expiresIn,
        long refreshExpiresIn,
        String tokenType) {
}
