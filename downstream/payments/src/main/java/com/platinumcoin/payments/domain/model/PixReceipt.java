package com.platinumcoin.payments.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Comprovante mock de um Pix enviado (POC: nada é persistido, dinheiro não se move). */
public record PixReceipt(
        String id,
        String status,
        String accountId,
        String pixKey,
        BigDecimal amount,
        Instant createdAt) {
}
