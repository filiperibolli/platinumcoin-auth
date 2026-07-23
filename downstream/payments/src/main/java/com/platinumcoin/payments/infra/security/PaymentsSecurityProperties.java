package com.platinumcoin.payments.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Audiência que este serviço exige nos tokens (ADR-006). */
@ConfigurationProperties("platinumcoin.payments")
public record PaymentsSecurityProperties(String requiredAudience) {
}
