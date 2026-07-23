package com.platinumcoin.auth.infra.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.platinumcoin.auth.domain.error.EmailAlreadyRegisteredException;
import com.platinumcoin.auth.domain.error.IdentityProviderUnavailableException;
import com.platinumcoin.auth.domain.error.InvalidCredentialsException;
import com.platinumcoin.auth.domain.model.AuthTokens;
import com.platinumcoin.auth.domain.model.RegisteredUser;
import com.platinumcoin.auth.domain.model.UserRegistration;
import com.platinumcoin.auth.domain.port.IdentityProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
                "emailVerified", true,
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
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("refresh_expires_in") long refreshExpiresIn,
            @JsonProperty("token_type") String tokenType) {
    }
}
