package com.platinumcoin.auth.domain.service;

import com.platinumcoin.auth.domain.model.AuthTokens;
import com.platinumcoin.auth.domain.model.Cpf;
import com.platinumcoin.auth.domain.model.RegisteredUser;
import com.platinumcoin.auth.domain.model.UserRegistration;
import com.platinumcoin.auth.domain.port.IdentityProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Casos de uso de registro e login. O accountId nasce aqui (UUID) e é gravado
 * no IdP como atributo do usuário — fonte da verdade no Keycloak (ADR-003).
 */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final IdentityProvider identityProvider;

    public AuthService(IdentityProvider identityProvider) {
        this.identityProvider = identityProvider;
    }

    public RegisteredUser register(String email, String password, String fullName, String rawCpf) {
        Cpf cpf = Cpf.parse(rawCpf);
        String accountId = UUID.randomUUID().toString();
        return identityProvider.register(new UserRegistration(email, password, fullName, cpf, accountId));
    }

    public AuthTokens login(String email, String password) {
        AuthTokens tokens = identityProvider.login(email, password);
        // Sem e-mail nem token no log: o correlationId do MDC amarra o E2E entre serviços.
        log.info("Login bem-sucedido");
        return tokens;
    }

    public AuthTokens refresh(String refreshToken) {
        return identityProvider.refresh(refreshToken);
    }

    public void logout(String refreshToken) {
        identityProvider.logout(refreshToken);
    }
}
