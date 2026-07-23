package com.platinumcoin.payments;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fatia 3 contra um Keycloak real (Testcontainers, realm-export):
 * (a) token válido → Pix com accountId do token; (b) 401 para token
 * ausente/malformado/aud errado; (c) regra de ouro (body divergente);
 * (d) token forjado (outra chave) recusado via JWKS.
 * Expiração (exp/nbf) fica com o JwtTimestampValidator com skew de 60s —
 * não dá para observar num teste rápido sem esperar o skew inteiro.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class PixFlowIntegrationTests {

    private static final String ALICE_ACCOUNT_ID = "7f8b1e7e-2a4d-4d6e-9c1a-5b3f2a9d0c11";

    @Container
    static final KeycloakContainer KEYCLOAK =
            new KeycloakContainer("quay.io/keycloak/keycloak:26.6.2")
                    .withRealmImportFile("realm-export.json");

    @DynamicPropertySource
    static void keycloakProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> issuer());
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> issuer() + "/protocol/openid-connect/certs");
    }

    private static String issuer() {
        return KEYCLOAK.getAuthServerUrl() + "/realms/platinumcoin";
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void pixDebitsAccountIdFromToken() {
        String token = loginToken("platinumcoin-harness", "alice@platinumcoin.dev");

        ResponseEntity<Map> response = rest.postForEntity("/v1/pix",
                pixRequest(token, Map.of("pixKey", "bob@banco.dev", "amount", 42.50)),
                Map.class);

        assertEquals(201, response.getStatusCode().value(), "Pix válido deve retornar 201");
        assertEquals(ALICE_ACCOUNT_ID, response.getBody().get("accountId"),
                "a conta debitada deve ser a do claim do token");
        assertNotNull(response.getBody().get("id"), "comprovante deve ter id");
        assertEquals("COMPLETED", response.getBody().get("status"));
    }

    @Test
    void divergentBodyAccountIdIsRejected() {
        String token = loginToken("platinumcoin-harness", "alice@platinumcoin.dev");

        ResponseEntity<String> response = rest.postForEntity("/v1/pix",
                pixRequest(token, Map.of(
                        "pixKey", "bob@banco.dev",
                        "amount", 42.50,
                        "accountId", "00000000-0000-0000-0000-000000000000")),
                String.class);

        assertEquals(422, response.getStatusCode().value(),
                "accountId divergente no corpo deve ser rejeitado (regra de ouro)");
        assertTrue(response.getBody().contains("ACCOUNT_MISMATCH"));
        assertTrue(response.getBody().contains("correlationId"));
    }

    @Test
    void missingTokenIsProblemJson401() {
        ResponseEntity<String> response = rest.postForEntity("/v1/pix",
                jsonRequest(Map.of("pixKey", "bob@banco.dev", "amount", 10)),
                String.class);

        assertEquals(401, response.getStatusCode().value());
        assertTrue(response.getHeaders().getContentType().toString()
                .startsWith("application/problem+json"));
        assertTrue(response.getBody().contains("TOKEN_INVALID"));
    }

    @Test
    void malformedTokenIs401() {
        ResponseEntity<String> response = rest.postForEntity("/v1/pix",
                pixRequest("nao-e-um-jwt", Map.of("pixKey", "bob@banco.dev", "amount", 10)),
                String.class);

        assertEquals(401, response.getStatusCode().value());
        assertTrue(response.getBody().contains("TOKEN_INVALID"));
    }

    @Test
    void tokenWithoutPaymentsAudienceIs401() {
        // Mesmo realm, mesma chave, usuário com role: só falta o aud do payments.
        String token = loginToken("other-service-harness", "alice@platinumcoin.dev");

        ResponseEntity<String> response = rest.postForEntity("/v1/pix",
                pixRequest(token, Map.of("pixKey", "bob@banco.dev", "amount", 10)),
                String.class);

        assertEquals(401, response.getStatusCode().value(),
                "token de outra audiência não pode ser aceito (ADR-006)");
        assertTrue(response.getBody().contains("TOKEN_INVALID"));
    }

    @Test
    void forgedTokenSignedByAnotherKeyIs401() throws Exception {
        // Claims perfeitos (iss/aud/role/accountId), mas assinado por uma chave que
        // não está no JWKS do realm — a validação de assinatura tem que barrar.
        RSAKey forgedKey = new RSAKeyGenerator(2048).keyID("forged-key").generate();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .audience("platinumcoin-payments")
                .subject("attacker")
                .claim("accountId", ALICE_ACCOUNT_ID)
                .claim("realm_access", Map.of("roles", List.of("customer")))
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .build();
        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(forgedKey.getKeyID()).build(),
                claims);
        forged.sign(new RSASSASigner(forgedKey));

        ResponseEntity<String> response = rest.postForEntity("/v1/pix",
                pixRequest(forged.serialize(), Map.of("pixKey", "bob@banco.dev", "amount", 10)),
                String.class);

        assertEquals(401, response.getStatusCode().value(),
                "token forjado deve falhar na verificação de assinatura via JWKS");
        assertTrue(response.getBody().contains("TOKEN_INVALID"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void idempotentReplayReturnsSameReceiptWithoutSecondDebit() {
        // Fatia 6 — cenário-alvo: o cliente deu timeout e reenviou. Mesma key + mesmo
        // payload → mesma resposta, um débito só.
        String token = loginToken("platinumcoin-harness", "alice@platinumcoin.dev");
        Map<String, Object> body = Map.of("pixKey", "bob@banco.dev", "amount", 42.50);

        ResponseEntity<Map> first = rest.postForEntity("/v1/pix",
                pixRequest(token, "it-replay-key", body), Map.class);
        ResponseEntity<Map> replay = rest.postForEntity("/v1/pix",
                pixRequest(token, "it-replay-key", body), Map.class);

        assertEquals(201, first.getStatusCode().value());
        assertEquals(201, replay.getStatusCode().value(), "replay devolve a mesma resposta");
        assertEquals(first.getBody().get("id"), replay.getBody().get("id"),
                "reenvio com a mesma Idempotency-Key não pode gerar um segundo comprovante");
    }

    @Test
    void idempotencyKeyReusedWithDifferentPayloadIs409() {
        String token = loginToken("platinumcoin-harness", "alice@platinumcoin.dev");

        rest.postForEntity("/v1/pix",
                pixRequest(token, "it-conflict-key", Map.of("pixKey", "bob@banco.dev", "amount", 42.50)),
                Map.class);
        ResponseEntity<String> conflict = rest.postForEntity("/v1/pix",
                pixRequest(token, "it-conflict-key", Map.of("pixKey", "bob@banco.dev", "amount", 99.99)),
                String.class);

        assertEquals(409, conflict.getStatusCode().value(),
                "mesma key com payload diferente não é retry — é conflito");
        assertTrue(conflict.getBody().contains("IDEMPOTENCY_CONFLICT"));
        assertTrue(conflict.getBody().contains("correlationId"));
    }

    @Test
    void supportTokenOnPixIs403() {
        // Fatia 4 — RBAC: carol tem `support` (e não `customer`); autenticada, mas sem
        // permissão de enviar Pix. Token bom + role errada = 403, não 401.
        String token = loginToken("platinumcoin-harness", "carol@platinumcoin.dev");

        ResponseEntity<String> response = rest.postForEntity("/v1/pix",
                pixRequest(token, Map.of("pixKey", "bob@banco.dev", "amount", 10)),
                String.class);

        assertEquals(403, response.getStatusCode().value(),
                "token support (sem role customer) deve receber 403 no /v1/pix");
        assertTrue(response.getBody().contains("ACCESS_DENIED"));
    }

    @Test
    void supportTokenReadsAdminReceipts() {
        String customerToken = loginToken("platinumcoin-harness", "alice@platinumcoin.dev");
        rest.postForEntity("/v1/pix",
                pixRequest(customerToken, Map.of("pixKey", "bob@banco.dev", "amount", 7.77)),
                Map.class);

        String supportToken = loginToken("platinumcoin-harness", "carol@platinumcoin.dev");
        ResponseEntity<List> response = rest.exchange("/v1/admin/receipts", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(supportToken)), List.class);

        assertEquals(200, response.getStatusCode().value(),
                "role support deve acessar a visão administrativa");
        assertTrue(response.getBody().size() >= 1, "deve listar o comprovante do Pix enviado");
    }

    @Test
    void customerTokenOnAdminIs403() {
        String token = loginToken("platinumcoin-harness", "alice@platinumcoin.dev");

        ResponseEntity<String> response = rest.exchange("/v1/admin/receipts", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);

        assertEquals(403, response.getStatusCode().value(),
                "role customer não pode acessar a visão administrativa");
        assertTrue(response.getBody().contains("ACCESS_DENIED"));
    }

    /** Direct grant direto no Keycloak: o payments não participa da emissão. */
    private String loginToken(String clientId, String username) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", clientId);
        form.add("username", username);
        form.add("password", "Seed@12345");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        Map<?, ?> body = new RestTemplate().postForObject(
                issuer() + "/protocol/openid-connect/token",
                new HttpEntity<>(form, headers),
                Map.class);
        String token = (String) body.get("access_token");
        assertNotNull(token, "login no Keycloak deve emitir access token");
        return token;
    }

    private HttpEntity<Map<String, Object>> pixRequest(String bearer, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<Map<String, Object>> pixRequest(
            String bearer, String idempotencyKey, Map<String, Object> body) {
        HttpEntity<Map<String, Object>> request = pixRequest(bearer, body);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(request.getHeaders());
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(body, headers);
    }

    private HttpHeaders bearerHeaders(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return headers;
    }

    private HttpEntity<Map<String, Object>> jsonRequest(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
