package com.platinumcoin.payments.domain.error;

/**
 * Mesma Idempotency-Key com payload diferente: não é um retry, é outra operação
 * tentando reusar a key — conflito (409), nunca um segundo débito.
 */
public class IdempotencyConflictException extends RuntimeException {
}
