package com.platinumcoin.payments.api.dto;

import com.platinumcoin.payments.domain.model.PixReceipt;

import java.math.BigDecimal;
import java.time.Instant;

public record PixResponse(
        String id,
        String status,
        String accountId,
        String pixKey,
        BigDecimal amount,
        Instant createdAt) {

    public static PixResponse from(PixReceipt receipt) {
        return new PixResponse(
                receipt.id(),
                receipt.status(),
                receipt.accountId(),
                receipt.pixKey(),
                receipt.amount(),
                receipt.createdAt());
    }
}
