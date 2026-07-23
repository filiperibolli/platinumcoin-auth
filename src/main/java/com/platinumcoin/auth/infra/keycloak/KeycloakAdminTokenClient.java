package com.platinumcoin.auth.infra.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.platinumcoin.auth.domain.error.IdentityProviderUnavailableException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;

/**
 * Token de service account (client_credentials) do client auth-service-admin,
 * que só tem manage-users/view-users do realm — menor privilégio, sem admin master.
 * Cache simples até perto da expiração.
 */
@Component
public class KeycloakAdminTokenClient {

    private static final long EXPIRY_SAFETY_MARGIN_SECONDS = 30;

    private final RestClient restClient;
    private final KeycloakProperties properties;

    private String cachedToken;
    private Instant cachedTokenExpiry = Instant.EPOCH;

    public KeycloakAdminTokenClient(RestClient.Builder restClientBuilder, KeycloakProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    public synchronized String accessToken() {
        if (Instant.now().isBefore(cachedTokenExpiry)) {
            return cachedToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.adminClientId());
        form.add("client_secret", properties.adminClientSecret());
        try {
            TokenResponse response = restClient.post()
                    .uri(properties.tokenEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            cachedToken = response.accessToken();
            cachedTokenExpiry = Instant.now().plusSeconds(
                    Math.max(0, response.expiresIn() - EXPIRY_SAFETY_MARGIN_SECONDS));
            return cachedToken;
        } catch (RestClientException e) {
            throw new IdentityProviderUnavailableException("Falha ao obter token de service account", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {
    }
}
