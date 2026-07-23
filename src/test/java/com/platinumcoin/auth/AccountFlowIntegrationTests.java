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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Fluxos de conta da Fatia 5 contra Keycloak + Mailhog reais na mesma network:
 * o realm-export aponta o SMTP para o alias `mailhog`, e o teste lê a caixa
 * de entrada pela API HTTP do Mailhog — prova que o e-mail realmente saiu.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class AccountFlowIntegrationTests {

    static final Network NETWORK = Network.newNetwork();

    @Container
    static final GenericContainer<?> MAILHOG =
            new GenericContainer<>("mailhog/mailhog:v1.0.1")
                    .withNetwork(NETWORK)
                    .withNetworkAliases("mailhog")
                    .withExposedPorts(8025);

    @Container
    static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:26.6.2")
                    .withRealmImportFile("realm-export.json")
                    .withNetwork(NETWORK);

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
    void registerSendsVerificationEmail() {
        ResponseEntity<Map> register = rest.postForEntity("/v1/auth/register",
                Map.of(
                        "email", "carla@platinumcoin.dev",
                        "password", "S3nh@forte123",
                        "fullName", "Carla Verifica",
                        "cpf", "16899535009"),
                Map.class);
        assertEquals(201, register.getStatusCode().value());

        String inbox = awaitEmailTo("carla@platinumcoin.dev", "Verify email");
        assertTrue(inbox.contains("login-actions"),
                "e-mail de verificação deve trazer o link de ação do Keycloak");
    }

    @Test
    void verifyEmailResendReturns202ForKnownAndUnknownEmail() {
        ResponseEntity<Void> known = rest.postForEntity("/v1/auth/verify-email",
                Map.of("email", "alice@platinumcoin.dev"), Void.class);
        assertEquals(202, known.getStatusCode().value());
        awaitEmailTo("alice@platinumcoin.dev", "Verify email");

        // Mesma resposta para e-mail inexistente: nada de oráculo de cadastro.
        ResponseEntity<Void> unknown = rest.postForEntity("/v1/auth/verify-email",
                Map.of("email", "ninguem@platinumcoin.dev"), Void.class);
        assertEquals(202, unknown.getStatusCode().value());
    }

    @Test
    void forgotPasswordSendsResetActionEmail() {
        rest.postForEntity("/v1/auth/register",
                Map.of(
                        "email", "ellen@platinumcoin.dev",
                        "password", "S3nh@forte123",
                        "fullName", "Ellen Esqueceu",
                        "cpf", "52998224725"),
                Map.class);
        ResponseEntity<Void> response = rest.postForEntity("/v1/auth/forgot-password",
                Map.of("email", "ellen@platinumcoin.dev"), Void.class);
        assertEquals(202, response.getStatusCode().value());

        // O link conclui o UPDATE_PASSWORD no próprio Keycloak (ação nativa, ADR-009).
        // "Update Your Account" é o assunto do executeActions — distingue do e-mail de verificação.
        String inbox = awaitEmailTo("ellen@platinumcoin.dev", "Update Your Account");
        assertTrue(inbox.contains("login-actions"),
                "e-mail de reset deve trazer o link da ação UPDATE_PASSWORD");
    }

    @Test
    @SuppressWarnings("unchecked")
    void changePasswordReAuthenticatesRotatesAndRevokesSessions() {
        rest.postForEntity("/v1/auth/register",
                Map.of(
                        "email", "dave@platinumcoin.dev",
                        "password", "S3nh@forte123",
                        "fullName", "Dave Trocador",
                        "cpf", "74769858000"),
                Map.class);
        ResponseEntity<Map> login = rest.postForEntity("/v1/auth/login",
                Map.of("email", "dave@platinumcoin.dev", "password", "S3nh@forte123"), Map.class);
        assertEquals(200, login.getStatusCode().value());
        String accessToken = (String) login.getBody().get("accessToken");
        String refreshToken = (String) login.getBody().get("refreshToken");

        // Token válido mas senha atual errada → 401: o token sozinho não troca senha.
        ResponseEntity<String> wrongCurrent = rest.exchange("/v1/auth/change-password",
                HttpMethod.POST,
                bearer(accessToken, Map.of(
                        "currentPassword", "senhaErrada!123", "newPassword", "N0v@senha456")),
                String.class);
        assertEquals(401, wrongCurrent.getStatusCode().value());
        assertTrue(wrongCurrent.getBody().contains("INVALID_CREDENTIALS"));

        ResponseEntity<Void> changed = rest.exchange("/v1/auth/change-password",
                HttpMethod.POST,
                bearer(accessToken, Map.of(
                        "currentPassword", "S3nh@forte123", "newPassword", "N0v@senha456")),
                Void.class);
        assertEquals(204, changed.getStatusCode().value());

        ResponseEntity<String> oldPassword = rest.postForEntity("/v1/auth/login",
                Map.of("email", "dave@platinumcoin.dev", "password", "S3nh@forte123"), String.class);
        assertEquals(401, oldPassword.getStatusCode().value(), "senha antiga não pode mais logar");

        ResponseEntity<Map> newPassword = rest.postForEntity("/v1/auth/login",
                Map.of("email", "dave@platinumcoin.dev", "password", "N0v@senha456"), Map.class);
        assertEquals(200, newPassword.getStatusCode().value(), "senha nova loga");

        // Sessões anteriores foram revogadas junto com a troca.
        ResponseEntity<String> oldRefresh = rest.postForEntity("/v1/auth/refresh",
                Map.of("refreshToken", refreshToken), String.class);
        assertEquals(401, oldRefresh.getStatusCode().value(),
                "refresh emitido antes da troca de senha deve estar revogado");
    }

    @Test
    void changePasswordWithoutTokenIs401() {
        ResponseEntity<String> response = rest.postForEntity("/v1/auth/change-password",
                Map.of("currentPassword", "x", "newPassword", "N0v@senha456"), String.class);
        assertEquals(401, response.getStatusCode().value());
    }

    private HttpEntity<Map<String, String>> bearer(String accessToken, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return new HttpEntity<>(body, headers);
    }

    /** Consulta a API do Mailhog até chegar e-mail para o destinatário com o assunto esperado. */
    private String awaitEmailTo(String email, String subjectContains) {
        String searchUrl = "http://" + MAILHOG.getHost() + ":" + MAILHOG.getMappedPort(8025)
                + "/api/v2/search?kind=to&query=" + email;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String body = rest.getForObject(searchUrl, String.class);
            if (body != null && body.contains(subjectContains)) {
                return body;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrompido aguardando e-mail");
            }
        }
        return fail("nenhum e-mail chegou ao Mailhog para " + email);
    }
}
