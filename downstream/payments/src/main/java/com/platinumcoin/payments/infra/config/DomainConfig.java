package com.platinumcoin.payments.infra.config;

import com.platinumcoin.payments.domain.service.PixService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Domínio livre de Spring: os serviços de domínio viram beans aqui na infra. */
@Configuration
public class DomainConfig {

    @Bean
    public PixService pixService() {
        return new PixService();
    }
}
