# PLAN — platinumcoin-auth

> Plano faseado em **fatias verticais**. Cada fatia entrega um fluxo utilizável de ponta a
> ponta (não uma camada horizontal). Nada de código de aplicação até este plano ser aprovado.

## Tese do projeto (uma frase)

Um IdP real (Keycloak) emite **JWT RS256** com um claim `accountId`; serviços downstream
**confiam sem poder emitir** — validam a assinatura via **JWKS**, exigem o **`aud`** destinado a
eles, autorizam por role/scope e **debitam o `accountId` do token, nunca do corpo**.

## Decisões já tomadas (kickoff)

| Decisão | Escolha | ADR |
|---|---|---|
| IdP local | **Keycloak 26.6.x** (Quarkus), tag de patch fixa, Postgres p/ persistência | ADR-001 |
| Assinatura | **RS256 + validação via JWKS** (sem segredo compartilhado) | ADR-002 |
| Fonte da verdade do `accountId` | **User attribute no Keycloak**; auth-service **stateless** | ADR-003 |
| Provisionamento do realm | **Terraform** (`keycloak/keycloak`) p/ demo; **realm-export JSON** p/ testes | ADR-004 |
| Validação no consumidor | **JWKS local** no payments (introspection documentada como alternativa) | ADR-005 |
| Isolamento entre serviços | **Validação de `aud`**; nenhum serviço aceita token de outra audiência | ADR-006 |
| Compartilhamento de código | **Sem common-lib**; cada serviço configura seu resource server (duplicação proposital) | ADR-007 |
| Login p/ testes | **Direct Access Grants** num client de harness; auth-code+PKCE é a postura de prod | ADR-008 |
| Topologia do auth-service | **Fachada/BFF** sobre o Keycloak (dono de `/v1/auth/*`) | ADR-003 (nota) |

**Gotchas resolvidos cedo:** (a) o mapper de `accountId` precisa de **Add to access token = ON**;
(b) o **Declarative User Profile** (default no 26.x) exige declarar `accountId` no user profile do
realm (`keycloak_realm_user_profile`) senão o atributo é ignorado silenciosamente.

## Convenções (valem para os dois serviços)

- Java 21 + Spring Boot 3 + Maven. **Single-module** por serviço, `pom.xml` próprio, build próprio.
- Layout `api / domain / infra`; regra de dependência aponta pra dentro (sem ArchUnit, é diretriz).
- Validação de token: **Spring Security Resource Server** (`oauth2.resourceserver.jwt`).
- Erros: **RFC 7807** (`application/problem+json`) com `code` e `correlationId`; nunca stack trace.
- Log **estruturado JSON** (SLF4J), `correlationId` no MDC; propagação via `X-Correlation-Id`.
- REST sob `/v1/...`; **records** para DTOs; token nunca logado.

---

## Fatias

> O **critério de aceite ("pronto quando")** de cada fatia está em [`docs/dod.md`](docs/dod.md) —
> use-o como o portão de conclusão. As seções abaixo dão o *objetivo* e o *o que é construído*.

### Fatia 1 — Login e identidade confiável *(núcleo da tese)*
**Objetivo:** cadastrar cliente, logar e obter access+refresh com `accountId` no token; endpoint
protegido que valida via JWKS.
**Construído:**
- `docker-compose`: Keycloak 26.6 + Postgres (mesma rede).
- Terraform: realm, client de harness (Direct Access Grants), client scope `account` +
  User Attribute mapper (`accountId` no access token), `realm_user_profile` declarando `accountId`,
  usuário de seed.
- `platinumcoin-auth` (Spring Boot, `api/domain/infra`): `POST /v1/auth/register` (via Admin API,
  gera+grava `accountId`), `POST /v1/auth/login` (direct grant → tokens), `GET /v1/me`
  (resource server / JWKS → identidade + `accountId`).
- Cross-cutting: filtro de `correlationId` + MDC, `@RestControllerAdvice` RFC 7807, Actuator health.

**Como verifico localmente:**
```
docker-compose up -d && (cd terraform && terraform init && terraform apply)
curl -sX POST .../v1/auth/register -d '{...}'
curl -sX POST .../v1/auth/login    -d '{...}'   # -> access + refresh
# decodificar o access token: claim accountId presente
curl -s .../v1/me -H "Authorization: Bearer $ACCESS"   # -> identidade + accountId
curl -si .../v1/me                                      # sem token -> 401 problem+json
```

### Fatia 2 — Ciclo de sessão: refresh + logout/revogação
**Objetivo:** completar o ciclo de vida do token.
**Construído:** `POST /v1/auth/refresh` (rotação de refresh), `POST /v1/auth/logout`
(`end_session`/back-channel), TTLs de access/refresh configurados no realm.
**Como verifico:** refresh → novo par; refresh **reusado** após rotação → falha; após logout,
refresh subsequente → 401.

### Fatia 3 — platinumcoin-payments *(prova a assimetria + a regra de ouro)*
**Objetivo:** `POST /v1/pix` que só executa para requisição autenticada/autorizada e debita o
`accountId` **do token**.
**Construído:**
- `downstream/payments` (Spring Boot, single-module, **sem credencial do Keycloak**): resource
  server (`issuer-uri`/`jwk-set-uri`), **validador de `aud`** custom, `JwtAuthenticationConverter`
  (role `customer` → authority), `POST /v1/pix` (mock de negócio) que **ignora/rejeita** `accountId`
  no corpo, RFC 7807, propagação do `correlationId` recebido do auth.
- Terraform: role `customer`, `aud`/client scope destinado ao payments, mapper de audiência.

**Como verifico (E2E entre os dois serviços):**
```
# login no auth -> token -> chamada ao payments
curl -s .../v1/pix -H "Authorization: Bearer $ACCESS" -d '{...}'   # executa com accountId do token
# corpo com accountId divergente -> prevalece o do token (ou rejeita com erro claro)
# token ausente / expirado / aud errado -> 401
# mesmo correlationId aparece nos logs JSON do auth E do payments
```

### Fatia 4 — Autorização RBAC (customer vs. support) + testes negativos de confiança
**Objetivo:** tornar a autorização explícita e fechar a narrativa de segurança.
**Construído:** role `support` (sem `pix:send`), endpoint administrativo protegido, mapeamento de
roles → authorities.
**Como verifico:** token `support` no `/v1/pix` → **403**; token de **outra audiência** → 401;
**token forjado** (assinado por outra chave) → 401 via JWKS.

### Fatia 5 — Fluxos de conta de fintech
**Objetivo:** confirmação de cadastro e recuperação de senha.
**Construído:** `/v1/auth` delegando às ações nativas do Keycloak (verify-email, reset-password,
change-password), SMTP de dev (ex.: Mailhog) no compose, reenvio de código.
**Como verifico:** registro dispara e-mail de verificação (Mailhog); fluxo de reset e troca de senha.

### Fatia 6 — Idempotência no `/v1/pix`
**Objetivo:** `Idempotency-Key` com dedup simples (in-memory) para reenvio por timeout não debitar
duas vezes. É escopo de negócio, não de identidade — entra por último; fallback é o parágrafo no
README ("em produção isto teria idempotência").Postman e html para chamar e testar com exemplos de request e jornada.

---

## Testes e observabilidade (enxuto)
- Actuator health apenas (sem métricas/dashboards).
- Integração-chave via **Testcontainers Keycloak** (realm-export JSON): login → token → validação
  JWKS; refresh; um 401.
- Payments: (a) token válido ⇒ Pix com `accountId` do token; (b) 401 com ausente/expirado/
  mal-assinado/aud errado; (c) regra de ouro (body divergente); (d) token forjado recusado.
- **Runbook manual** forte (curl por fluxo, incluindo o E2E entre serviços mostrando o mesmo
  `correlationId`) e/ou coleção Postman. E2E funcionando na mão = critério de "pronto".

## ADRs iniciais a registrar (leves, um por decisão de peso)
- **ADR-001** — Keycloak como IdP local; conceitos portáveis para Cognito (o que transfere / o que não).
- **ADR-002** — RS256 + JWKS vs. HS256 + segredo compartilhado.
- **ADR-003** — Onde nasce/vive o `accountId` (Keycloak como SoT; auth-service stateless; evolução).
- **ADR-004** — Provisionamento do realm por Terraform (+ export JSON para testes).
- **ADR-005** — Validação no consumidor: JWKS local vs. introspection/chamada ao auth.
- **ADR-006** — Validação de `aud` entre serviços (confused-deputy / replay).
- **ADR-007** — Sem common-lib entre serviços (validação duplicada de propósito).
- **ADR-008** — Direct Access Grants para o harness; auth-code+PKCE como postura de produção.

## Entregáveis de documentação
- README raiz (cartão do projeto): propósito, os dois serviços e portas, subir (compose + terraform
  apply), variáveis de ambiente, curl E2E que atravessa os dois serviços.
- ADRs acima em `docs/adr/`.

---

## Aguardando aprovação
Implementação começa **uma fatia por vez**, em incrementos verificáveis, após seu OK neste plano.
