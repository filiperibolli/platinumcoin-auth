package com.platinumcoin.payments.domain.service;

import com.platinumcoin.payments.domain.error.IdempotencyConflictException;
import com.platinumcoin.payments.domain.model.PixReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mock de negócio: "debita" a conta e devolve um comprovante. O accountId chega
 * sempre da camada de API, extraído do token — nunca do corpo (regra de ouro).
 * Comprovantes ficam em memória (POC, sem persistência) para a visão de suporte.
 *
 * Fatia 6 — idempotência: com `Idempotency-Key`, o reenvio (retry por timeout)
 * devolve o MESMO comprovante sem segundo débito; mesma key com payload diferente
 * é conflito. Dedup in-memory com TTL — corte consciente de POC (ADR-010).
 */
public class PixService {

    private static final Logger log = LoggerFactory.getLogger(PixService.class);

    private final List<PixReceipt> receipts = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, IdempotencyEntry> idempotency = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration idempotencyTtl;

    public PixService(Clock clock, Duration idempotencyTtl) {
        this.clock = clock;
        this.idempotencyTtl = idempotencyTtl;
    }

    public PixReceipt send(String accountId, String pixKey, BigDecimal amount, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return debit(accountId, pixKey, amount);
        }
        // Escopo por conta: a key de um cliente nunca colide com a de outro, nem
        // serve para ler comprovante alheio (a conta vem do token, como sempre).
        String dedupKey = accountId + "|" + idempotencyKey;
        String fingerprint = fingerprint(pixKey, amount);
        evictExpired();

        // computeIfAbsent é atômico por key: dois reenvios simultâneos debitam uma vez só.
        AtomicBoolean debited = new AtomicBoolean(false);
        IdempotencyEntry entry = idempotency.computeIfAbsent(dedupKey, key -> {
            debited.set(true);
            return new IdempotencyEntry(
                    fingerprint, debit(accountId, pixKey, amount), clock.instant().plus(idempotencyTtl));
        });
        if (!entry.fingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException();
        }
        if (!debited.get()) {
            log.info("Pix replay idempotente (sem novo débito): receiptId={}, accountId={}",
                    entry.receipt().id(), accountId);
        }
        return entry.receipt();
    }

    public List<PixReceipt> receipts() {
        return List.copyOf(receipts);
    }

    private PixReceipt debit(String accountId, String pixKey, BigDecimal amount) {
        PixReceipt receipt = new PixReceipt(
                UUID.randomUUID().toString(),
                "COMPLETED",
                accountId,
                pixKey,
                amount,
                clock.instant());
        receipts.add(receipt);
        log.info("Pix mock executado: receiptId={}, accountId={}, amount={}",
                receipt.id(), accountId, amount);
        return receipt;
    }

    /** Normaliza o valor (42.50 == 42.5): retry honesto não pode virar conflito por escala. */
    private String fingerprint(String pixKey, BigDecimal amount) {
        return pixKey + "|" + amount.stripTrailingZeros().toPlainString();
    }

    /** Expiração preguiçosa a cada uso: suficiente para POC, sem thread de limpeza. */
    private void evictExpired() {
        Instant now = clock.instant();
        idempotency.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private record IdempotencyEntry(String fingerprint, PixReceipt receipt, Instant expiresAt) {
    }
}
