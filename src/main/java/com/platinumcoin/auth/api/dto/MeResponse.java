package com.platinumcoin.auth.api.dto;

public record MeResponse(
        String sub,
        String email,
        String name,
        String accountId) {
}
