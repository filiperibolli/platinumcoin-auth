package com.platinumcoin.auth.domain.model;

public record RegisteredUser(
        String userId,
        String email,
        String fullName,
        String cpf,
        String accountId) {
}
