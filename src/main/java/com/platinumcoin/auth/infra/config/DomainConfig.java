package com.platinumcoin.auth.infra.config;

import com.platinumcoin.auth.domain.port.IdentityProvider;
import com.platinumcoin.auth.domain.service.AuthService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring do domínio: o domain não conhece Spring, então os beans nascem aqui.
 */
@Configuration
public class DomainConfig {

    @Bean
    public AuthService authService(IdentityProvider identityProvider) {
        return new AuthService(identityProvider);
    }
}
