package com.platinumcoin.auth.domain.model;

public record UserRegistration(
        String email,
        String password,
        String fullName,
        Cpf cpf,
        String accountId) {
}
