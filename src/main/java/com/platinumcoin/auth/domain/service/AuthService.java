package com.platinumcoin.auth.domain.service;

import com.platinumcoin.auth.domain.model.AuthTokens;
import com.platinumcoin.auth.domain.model.Cpf;
import com.platinumcoin.auth.domain.model.RegisteredUser;
import com.platinumcoin.auth.domain.error.IdentityProviderUnavailableException;
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
        RegisteredUser user =
                identityProvider.register(new UserRegistration(email, password, fullName, cpf, accountId));
        // Best-effort: SMTP fora do ar não pode desfazer um cadastro que já existe no IdP.
        // O cliente reenvia depois via /v1/auth/verify-email.
        try {
            identityProvider.sendVerificationEmail(email);
        } catch (IdentityProviderUnavailableException e) {
            log.warn("Cadastro ok, mas falhou o envio do e-mail de verificação", e);
        }
        return user;
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

    public void resendVerificationEmail(String email) {
        identityProvider.sendVerificationEmail(email);
    }

    public void forgotPassword(String email) {
        identityProvider.sendPasswordResetEmail(email);
    }

    /**
     * Troca de senha autenticada. O Bearer token prova a sessão, mas não a posse da
     * senha — re-autenticamos com a senha atual antes de trocar, para que um token
     * vazado não baste para tomar a conta. O IdP revoga as demais sessões em seguida.
     */
    public void changePassword(String email, String currentPassword, String newPassword) {
        identityProvider.login(email, currentPassword);
        identityProvider.resetPassword(email, newPassword);
        log.info("Senha alterada; sessões ativas revogadas");
    }
}
