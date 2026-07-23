package com.platinumcoin.payments.domain.error;

/** O corpo trouxe um accountId divergente do claim do token — regra de ouro violada. */
public class AccountMismatchException extends RuntimeException {

    public AccountMismatchException() {
        super("accountId do corpo diverge do accountId do token");
    }
}
