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

    /**
     * Dispara o e-mail de verificação. E-mail desconhecido é no-op silencioso:
     * a resposta ao cliente é sempre a mesma (anti-enumeração de usuários).
     */
    void sendVerificationEmail(String email);

    /**
     * Dispara o e-mail de reset com a ação nativa UPDATE_PASSWORD do IdP.
     * O link do e-mail conclui o reset no próprio IdP — este serviço nunca
     * vê a senha nova. E-mail desconhecido é no-op silencioso (anti-enumeração).
     */
    void sendPasswordResetEmail(String email);

    /**
     * Define a nova senha e revoga as sessões ativas do usuário. A prova de posse
     * (senha atual) é responsabilidade de quem chama — ver AuthService.
     */
    void resetPassword(String email, String newPassword);
}
