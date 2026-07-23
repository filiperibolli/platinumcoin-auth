package com.platinumcoin.payments.infra.config;

import com.platinumcoin.payments.domain.service.PixService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/** Domínio livre de Spring: os serviços de domínio viram beans aqui na infra. */
@Configuration
public class DomainConfig {

    @Bean
    public PixService pixService(
            @Value("${platinumcoin.payments.idempotency-ttl}") Duration idempotencyTtl) {
        return new PixService(Clock.systemUTC(), idempotencyTtl);
    }
}
