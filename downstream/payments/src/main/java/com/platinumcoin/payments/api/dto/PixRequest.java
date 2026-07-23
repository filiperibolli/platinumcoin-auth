package com.platinumcoin.payments.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * O campo accountId existe só para ser conferido contra o token: se vier divergente
 * a requisição é rejeitada (regra de ouro — a conta debitada é sempre a do token).
 */
public record PixRequest(
        @NotBlank String pixKey,
        @NotNull @Positive BigDecimal amount,
        String accountId) {
}
