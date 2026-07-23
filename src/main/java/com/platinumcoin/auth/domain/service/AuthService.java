package com.platinumcoin.auth.domain.service;

import com.platinumcoin.auth.domain.model.AuthTokens;
import com.platinumcoin.auth.domain.model.Cpf;
import com.platinumcoin.auth.domain.model.RegisteredUser;
import com.platinumcoin.auth.domain.model.UserRegistration;
import com.platinumcoin.auth.domain.port.IdentityProvider;

import java.util.UUID;

/**
 * Casos de uso de registro e login. O accountId nasce aqui (UUID) e é gravado
 * no IdP como atributo do usuário — fonte da verdade no Keycloak (ADR-003).
 */
public class AuthService {

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
        return identityProvider.login(email, password);
    }
}
