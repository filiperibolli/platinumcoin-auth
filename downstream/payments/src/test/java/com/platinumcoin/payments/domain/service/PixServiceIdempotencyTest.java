package com.platinumcoin.payments.domain.service;

import com.platinumcoin.payments.domain.error.IdempotencyConflictException;
import com.platinumcoin.payments.domain.model.PixReceipt;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Dedup do Idempotency-Key isolado do HTTP: só o domínio, com clock controlado. */
class PixServiceIdempotencyTest {

    private static final String ACCOUNT_A = "7f8b1e7e-2a4d-4d6e-9c1a-5b3f2a9d0c11";
    private static final String ACCOUNT_B = "9a1c3d5e-7b2f-4a8c-8d0e-1f2a3b4c5d6e";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-07-23T12:00:00Z"));
    private final PixService service = new PixService(clock, TTL);

    @Test
    void sameKeyAndPayloadReturnsSameReceiptWithoutSecondDebit() {
        PixReceipt first = service.send(ACCOUNT_A, "bob@banco.dev", new BigDecimal("42.50"), "key-1");
        PixReceipt replay = service.send(ACCOUNT_A, "bob@banco.dev", new BigDecimal("42.50"), "key-1");

        assertEquals(first.id(), replay.id(), "replay deve devolver o MESMO comprovante");
        assertEquals(1, service.receipts().size(), "não pode haver segundo débito");
    }

    @Test
    void equivalentAmountScaleIsStillTheSamePayload() {
        PixReceipt first = service.send(ACCOUNT_A, "bob@banco.dev", new BigDecimal("42.50"), "key-1");
        PixReceipt replay = service.send(ACCOUNT_A, "bob@banco.dev", new BigDecimal("42.5"), "key-1");

        assertEquals(first.id(), replay.id(), "42.50 e 42.5 são o mesmo valor — retry honesto");
    }

    @Test
    void sameKeyWithDifferentPayloadIsConflictAndDoesNotDebit() {
        service.send(ACCOUNT_A, "bob@banco.dev", new BigDecimal("42.50"), "key-1");

        assertThrows(IdempotencyConflictException.class,
                () -> service.send(ACCOUNT_A, "bob@banco.dev", new BigDecimal("99.99"), "key-1"));
        assertEquals(1, service.receipts().size(), "conflito não pode debitar");
    }

    @Test
    void sameKeyOnDifferentAccountsAreIndependentOperations() {
        PixReceipt a = service.send(ACCOUNT_A, "bob@banco.dev", new BigDecimal("10"), "key-1");
        PixReceipt b = service.send(ACCOUNT_B, "bob@banco.dev", new BigDecimal("10"), "key-1");

        assertNotEquals(a.id(), b.id(), "a key é escopada por conta (do token)");
        assertEquals(2, service.receipts().size());
    }

    @Test
    void expiredKeyDebitsAgain() {
        PixReceipt first = service.send(ACCOUNT_A, "bob@banco.dev", new BigDecimal("10"), "key-1");
        clock.advance(TTL.plusSeconds(1));
        PixReceipt second = service.send(ACCOUNT_A, "bob@banco.dev", new BigDecimal("10"), "key-1");

        assertNotEquals(first.id(), second.id(),
                "após o TTL a proteção expira: é uma operação nova");
        assertEquals(2, service.receipts().size());
    }

    @Test
    void withoutKeyEveryCallDebits() {
        service.send(ACCOUNT_A, "bob@banco.dev", new BigDecimal("10"), null);
        service.send(ACCOUNT_A, "bob@banco.dev", new BigDecimal("10"), null);

        assertEquals(2, service.receipts().size(), "sem key não há dedup");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
