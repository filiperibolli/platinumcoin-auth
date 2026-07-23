package com.platinumcoin.auth;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fluxo da Fatia 1 contra um Keycloak real (Testcontainers, realm-export):
 * register → login (direct grant) → /v1/me validando RS256 via JWKS; e um 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class AuthFlowIntegrationTests {

    @Container
    static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:26.6.2")
                    .withRealmImportFile("realm-export.json");

    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> KEYCLOAK.getAuthServerUrl() + "/realms/platinumcoin");
        registry.add("platinumcoin.keycloak.base-url", KEYCLOAK::getAuthServerUrl);
        registry.add("platinumcoin.keycloak.admin-client-secret", () -> "test-admin-secret");
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void registerLoginAndMeHappyPath() {
        ResponseEntity<Map> register = rest.postForEntity("/v1/auth/register",
                Map.of(
                        "email", "bob@platinumcoin.dev",
                        "password", "S3nh@forte123",
                        "fullName", "Bob Tester",
                        "cpf", "529.982.247-25"),
                Map.class);
        assertEquals(201, register.getStatusCode().value(), "register deve retornar 201");
        String accountId = (String) register.getBody().get("accountId");
        assertNotNull(accountId, "register deve devolver o accountId gerado");

        ResponseEntity<Map> login = rest.postForEntity("/v1/auth/login",
                Map.of("email", "bob@platinumcoin.dev", "password", "S3nh@forte123"),
                Map.class);
        assertEquals(200, login.getStatusCode().value(), "login deve retornar 200");
        String accessToken = (String) login.getBody().get("accessToken");
        assertNotNull(accessToken);
        assertNotNull(login.getBody().get("refreshToken"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        ResponseEntity<Map> me = rest.exchange(
                "/v1/me", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertEquals(200, me.getStatusCode().value(), "/v1/me com token válido deve retornar 200");
        assertEquals(accountId, me.getBody().get("accountId"),
                "o accountId do /v1/me deve vir do claim do token, igual ao gerado no registro");
        assertEquals("bob@platinumcoin.dev", me.getBody().get("email"));
    }

    @Test
    void meWithoutTokenIsProblemJson401() {
        ResponseEntity<String> response = rest.getForEntity("/v1/me", String.class);
        assertEquals(401, response.getStatusCode().value());
        assertTrue(response.getHeaders().getContentType().toString()
                .startsWith("application/problem+json"));
        assertTrue(response.getBody().contains("TOKEN_INVALID"));
        assertTrue(response.getBody().contains("correlationId"));
    }
}
