package com.platinumcoin.auth.infra.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.platinumcoin.auth.domain.error.EmailAlreadyRegisteredException;
import com.platinumcoin.auth.domain.error.IdentityProviderUnavailableException;
import com.platinumcoin.auth.domain.error.InvalidCredentialsException;
import com.platinumcoin.auth.domain.error.InvalidRefreshTokenException;
import com.platinumcoin.auth.domain.model.AuthTokens;
import com.platinumcoin.auth.domain.model.RegisteredUser;
import com.platinumcoin.auth.domain.model.UserRegistration;
import com.platinumcoin.auth.domain.port.IdentityProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapter Keycloak da porta IdentityProvider.
 * Registro via Admin REST API (service account); login via Direct Access Grant
 * no client público de harness (ADR-008 — em produção seria auth-code+PKCE).
 */
@Component
public class KeycloakIdentityProvider implements IdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityProvider.class);

    private final RestClient restClient;
    private final KeycloakProperties properties;
    private final KeycloakAdminTokenClient adminTokenClient;

    public KeycloakIdentityProvider(
            RestClient.Builder restClientBuilder,
            KeycloakProperties properties,
            KeycloakAdminTokenClient adminTokenClient) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
        this.adminTokenClient = adminTokenClient;
    }

    @Override
    public RegisteredUser register(UserRegistration registration) {
        Map<String, Object> body = Map.of(
                "username", registration.email(),
                "email", registration.email(),
                "firstName", firstName(registration.fullName()),
                "lastName", lastName(registration.fullName()),
                "enabled", true,
                // Fatia 5: nasce não-verificado; o e-mail de verificação sai logo após o
                // cadastro. O realm não bloqueia login sem verificação (POC — ver ADR-009).
                "emailVerified", false,
                "attributes", Map.of(
                        "accountId", List.of(registration.accountId()),
                        "cpf", List.of(registration.cpf().digits())),
                "credentials", List.of(Map.of(
                        "type", "password",
                        "value", registration.password(),
                        "temporary", false)));
        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri(properties.adminUsersEndpoint())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminTokenClient.accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            String userId = userIdFromLocation(response.getHeaders().getLocation());
            log.info("Usuário registrado no IdP, userId={}, accountId={}", userId, registration.accountId());
            return new RegisteredUser(
                    userId,
                    registration.email(),
                    registration.fullName(),
                    registration.cpf().digits(),
                    registration.accountId());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                throw new EmailAlreadyRegisteredException();
            }
            throw new IdentityProviderUnavailableException(
                    "Admin API respondeu " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new IdentityProviderUnavailableException("Falha ao chamar a Admin API", e);
        }
    }

    @Override
    public AuthTokens login(String email, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", properties.harnessClientId());
        form.add("username", email);
        form.add("password", password);
        try {
            TokenResponse response = restClient.post()
                    .uri(properties.tokenEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            return new AuthTokens(
                    response.accessToken(),
                    response.refreshToken(),
                    response.expiresIn(),
                    response.refreshExpiresIn(),
                    response.tokenType());
        } catch (RestClientResponseException e) {
            // 400/401 do token endpoint (invalid_grant etc.) → credenciais inválidas, sem detalhar.
            if (e.getStatusCode().is4xxClientError()) {
                throw new InvalidCredentialsException();
            }
            throw new IdentityProviderUnavailableException(
                    "Token endpoint respondeu " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new IdentityProviderUnavailableException("Falha ao chamar o token endpoint", e);
        }
    }

    @Override
    public AuthTokens refresh(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", properties.harnessClientId());
        form.add("refresh_token", refreshToken);
        try {
            TokenResponse response = restClient.post()
                    .uri(properties.tokenEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            return new AuthTokens(
                    response.accessToken(),
                    response.refreshToken(),
                    response.expiresIn(),
                    response.refreshExpiresIn(),
                    response.tokenType());
        } catch (RestClientResponseException e) {
            // invalid_grant: expirado, já rotacionado (reuso) ou sessão revogada.
            if (e.getStatusCode().is4xxClientError()) {
                throw new InvalidRefreshTokenException();
            }
            throw new IdentityProviderUnavailableException(
                    "Token endpoint respondeu " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new IdentityProviderUnavailableException("Falha ao chamar o token endpoint", e);
        }
    }

    @Override
    public void logout(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.harnessClientId());
        form.add("refresh_token", refreshToken);
        try {
            restClient.post()
                    .uri(properties.logoutEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new InvalidRefreshTokenException();
            }
            throw new IdentityProviderUnavailableException(
                    "Logout endpoint respondeu " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new IdentityProviderUnavailableException("Falha ao chamar o logout endpoint", e);
        }
    }

    @Override
    public void sendVerificationEmail(String email) {
        findUserIdByEmail(email).ifPresentOrElse(
                userId -> adminPut(
                        properties.adminUserEndpoint(userId, "send-verify-email"), null,
                        "send-verify-email"),
                // No-op silencioso: quem chamou responde 202 igual (anti-enumeração).
                () -> log.debug("verify-email para e-mail não cadastrado; ignorando"));
    }

    @Override
    public void sendPasswordResetEmail(String email) {
        findUserIdByEmail(email).ifPresentOrElse(
                userId -> adminPut(
                        properties.adminUserEndpoint(userId, "execute-actions-email"),
                        List.of("UPDATE_PASSWORD"),
                        "execute-actions-email"),
                () -> log.debug("forgot-password para e-mail não cadastrado; ignorando"));
    }

    @Override
    public void resetPassword(String email, String newPassword) {
        String userId = findUserIdByEmail(email).orElseThrow(
                // Quem chama acabou de re-autenticar este e-mail; sumir aqui é anomalia do IdP.
                () -> new IdentityProviderUnavailableException(
                        "Usuário autenticado não encontrado na Admin API", null));
        adminPut(
                properties.adminUserEndpoint(userId, "reset-password"),
                Map.of("type", "password", "value", newPassword, "temporary", false),
                "reset-password");
        // Senha trocada ⇒ sessões antigas (e seus refresh tokens) não valem mais.
        try {
            restClient.post()
                    .uri(properties.adminUserEndpoint(userId, "logout"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminTokenClient.accessToken())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new IdentityProviderUnavailableException("Falha ao revogar sessões", e);
        }
    }

    private Optional<String> findUserIdByEmail(String email) {
        try {
            List<UserRepresentation> users = restClient.get()
                    .uri(properties.adminUsersEndpoint() + "?exact=true&email={email}", email)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminTokenClient.accessToken())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<UserRepresentation>>() {});
            return users == null || users.isEmpty()
                    ? Optional.empty()
                    : Optional.of(users.getFirst().id());
        } catch (RestClientException e) {
            throw new IdentityProviderUnavailableException("Falha ao consultar usuário na Admin API", e);
        }
    }

    private void adminPut(String uri, Object body, String action) {
        try {
            RestClient.RequestBodySpec spec = restClient.put()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminTokenClient.accessToken())
                    .contentType(MediaType.APPLICATION_JSON);
            (body == null ? spec : spec.body(body)).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new IdentityProviderUnavailableException("Falha na Admin API (" + action + ")", e);
        }
    }

    private String userIdFromLocation(URI location) {
        if (location == null) {
            throw new IdentityProviderUnavailableException("Admin API não retornou Location", null);
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private String firstName(String fullName) {
        int space = fullName.trim().indexOf(' ');
        return space < 0 ? fullName.trim() : fullName.trim().substring(0, space);
    }

    private String lastName(String fullName) {
        int space = fullName.trim().indexOf(' ');
        return space < 0 ? "" : fullName.trim().substring(space + 1);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserRepresentation(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("refresh_expires_in") long refreshExpiresIn,
            @JsonProperty("token_type") String tokenType) {
    }
}
