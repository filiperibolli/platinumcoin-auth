package com.platinumcoin.auth.api.dto;

public record RegisterResponse(
        String userId,
        String email,
        String fullName,
        String cpf,
        String accountId) {
}
