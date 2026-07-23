package com.platinumcoin.payments.domain.error;

/** Token autenticado mas sem o claim accountId — não há conta para debitar (fail-closed). */
public class MissingAccountClaimException extends RuntimeException {

    public MissingAccountClaimException() {
        super("token não carrega o claim accountId");
    }
}
