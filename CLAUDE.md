# CLAUDE.md — platinumcoin-auth

Guia de contexto para qualquer sessão. **Antes de codar, leia o `PLAN.md`** (a spec do projeto,
em fatias verticais) e pegue a próxima fatia ainda não iniciada.

## O que é

Serviço de identidade/autenticação de uma fintech de pagamentos instantâneos (Pix). Tese: usar um
**IdP real (Keycloak)** que emite **JWT RS256** com um claim `accountId`; serviços downstream
**confiam sem poder emitir** — validam via **JWKS**, exigem o **`aud`** deles, autorizam por
role/scope e **debitam o `accountId` do token, nunca do corpo**.

## Dois serviços (mesmo repo, builds independentes, sem common-lib)

| Serviço | Pasta | Porta | Papel |
|---|---|---|---|
| `platinumcoin-auth` | `/` (raiz) | 8081 | Fachada/BFF sobre o Keycloak: `/v1/auth/*`, `/v1/me` |
| `platinumcoin-payments` | `downstream/payments` | 8082 | Consumidor: `POST /v1/pix`, valida token via JWKS |
| Keycloak (IdP) | Docker | 8080 | Emite os tokens; realm `platinumcoin` |
| Postgres (Keycloak) | Docker | 5432 | Persistência do Keycloak |
| Mailhog (SMTP dev) | Docker | 1025/8025 | Recebe e-mails de verificação/reset; UI em 8025 |

O `payments` **não** tem credencial de admin nem segredo do Keycloak — só conhece issuer/JWKS.
A duplicação da config de resource server entre os serviços é **proposital** (validação
IdP-agnóstica, sem acoplamento com o emissor).

## Convenções (valem para os dois serviços)

- Java 21 LTS + Spring Boot 3 + Maven. **Single-module** por serviço (`pom.xml` próprio, sem parent).
- Layout `api / domain / infra`; dependência aponta pra dentro (domain não importa Spring/servlet/HTTP).
  Diretriz de organização, **sem ArchUnit** / sem portão de build.
- Validação de token: **Spring Security Resource Server** (`spring.security.oauth2.resourceserver.jwt`),
  issuer/jwk-set-uri do realm. Padrão e portável.
- Erros: **RFC 7807** (`application/problem+json`) com `code` e `correlationId`. Nunca stack trace.
- Log **estruturado JSON** (SLF4J), `correlationId` no MDC, propagado via header `X-Correlation-Id`
  (o cliente HTTP auth→payments repassa o mesmo id). Sem `System.out`; token nunca logado.
- REST sob `/v1/...`; **records** para DTOs.
- ADRs leves em `docs/adr/`, **escritos dentro da fatia** a que a decisão pertence (não upfront).

## Decisões travadas (não re-litigar — ver `docs/adr/` quando existir)

- **Keycloak 26.6.x** (Quarkus), tag de patch fixa, Postgres p/ persistência.
- **RS256 + validação JWKS** (sem segredo compartilhado).
- **`accountId`**: fonte da verdade é o **user attribute no Keycloak**; auth-service **stateless**.
- **auth-service = Fachada/BFF** sobre o Keycloak (dono de `/v1/auth/*`).
- **payments valida via JWKS local** (introspection é alternativa documentada).
- **Validação de `aud`** entre serviços; ninguém aceita token de outra audiência.
- **Sem common-lib** entre serviços.
- Realm por **Terraform** (`keycloak/keycloak`); **realm-export JSON** para testes (Testcontainers).
- **Direct Access Grants** num client de harness para login em testes/curl; auth-code+PKCE é a
  postura de produção.

### Gotchas do Keycloak 26.x (resolver na Fatia 1)
- O mapper de `accountId` precisa de **Add to access token = ON** (não só ID token/userinfo).
- **Declarative User Profile** é default: declarar `accountId` no user profile do realm
  (`keycloak_realm_user_profile`) senão o atributo é ignorado silenciosamente.

## Como subir / rodar (comandos existirão após a Fatia 1)

```
docker-compose up -d
cd terraform && terraform init && terraform apply
# auth: http://localhost:8081  | payments: http://localhost:8082 | keycloak: http://localhost:8080
```

## Como retomar em outra conversa

1. Leia `PLAN.md` (roadmap) e `docs/dod.md` (critério de aceite por fatia); escolha a próxima
   fatia não concluída.
2. Implemente o incremento seguindo o **DoD** daquela fatia em `docs/dod.md`.
3. Rode a verificação (curl/testes); mostre o resultado marcando o DoD.
4. Se a fatia carrega decisão de peso, escreva o ADR correspondente em `docs/adr/`.
