package com.platinumcoin.auth.api.dto;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        long refreshExpiresIn,
        String tokenType) {
}
