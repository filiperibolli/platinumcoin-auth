package com.platinumcoin.payments.domain.service;

import com.platinumcoin.payments.domain.model.PixReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mock de negócio: "debita" a conta e devolve um comprovante. O accountId chega
 * sempre da camada de API, extraído do token — nunca do corpo (regra de ouro).
 */
public class PixService {

    private static final Logger log = LoggerFactory.getLogger(PixService.class);

    public PixReceipt send(String accountId, String pixKey, BigDecimal amount) {
        PixReceipt receipt = new PixReceipt(
                UUID.randomUUID().toString(),
                "COMPLETED",
                accountId,
                pixKey,
                amount,
                Instant.now());
        log.info("Pix mock executado: receiptId={}, accountId={}, amount={}",
                receipt.id(), accountId, amount);
        return receipt;
    }
}
