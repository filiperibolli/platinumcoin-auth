package com.platinumcoin.auth.domain.port;

import com.platinumcoin.auth.domain.model.AuthTokens;
import com.platinumcoin.auth.domain.model.RegisteredUser;
import com.platinumcoin.auth.domain.model.UserRegistration;

/**
 * Porta para o IdP. O domínio não sabe que é Keycloak — só que alguém
 * cadastra usuários e troca credenciais por tokens.
 */
public interface IdentityProvider {

    RegisteredUser register(UserRegistration registration);

    AuthTokens login(String email, String password);

    AuthTokens refresh(String refreshToken);

    void logout(String refreshToken);
}
