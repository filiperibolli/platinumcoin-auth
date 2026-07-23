# platinumcoin-auth

Serviço de identidade/autenticação de uma fintech de pagamentos instantâneos (Pix) — projeto de
portfólio. Tese: um IdP real (**Keycloak**) emite **JWT RS256** com um claim `accountId`; serviços
downstream **confiam sem poder emitir** — validam via **JWKS**, exigem o `aud` deles e **debitam o
`accountId` do token, nunca do corpo**.

Roadmap em [`PLAN.md`](PLAN.md), critérios de aceite em [`docs/dod.md`](docs/dod.md), decisões em
[`docs/adr/`](docs/adr/).

## Os dois serviços

| Serviço | Pasta | Porta | Papel |
|---|---|---|---|
| `platinumcoin-auth` | `/` (raiz) | 8081 | Fachada/BFF sobre o Keycloak: `/v1/auth/*`, `/v1/me` |
| `platinumcoin-payments` | `downstream/payments` | 8082 | Consumidor: `POST /v1/pix`, valida token via JWKS |

O `payments` **não tem credencial nenhuma do Keycloak** — a confiança dele cabe em duas URLs
públicas (`issuer-uri` + `jwk-set-uri`). É a assimetria da tese, visível na config (ADR-005/007).

## Subir

```bash
docker-compose up -d                          # Keycloak 26.6 (8080) + Postgres
(cd terraform && terraform init && terraform apply)   # realm platinumcoin
mvn spring-boot:run                           # auth-service na 8081
(cd downstream/payments && mvn spring-boot:run)       # payments na 8082
```

## Endpoints (`/v1`)

| Endpoint | Serviço | O quê |
|---|---|---|
| `POST /v1/auth/register` | auth | Cria usuário via Admin API (service account de menor privilégio); gera `accountId` |
| `POST /v1/auth/login` | auth | Direct grant (harness) → access + refresh |
| `POST /v1/auth/refresh` | auth | Troca refresh por novo par (rotação ativa) |
| `POST /v1/auth/logout` | auth | Revoga a sessão no IdP |
| `GET /v1/me` | auth | Identidade extraída do token (RS256 via JWKS) |
| `POST /v1/pix` | payments | Exige role `customer` + `aud` do payments; **debita o `accountId` do token** |
| `GET /v1/admin/receipts` | payments | Exige role `support`; visão de atendimento dos comprovantes (POC: em memória) |

## E2E entre os serviços (a tese na prática)

```bash
CID="e2e-$(date +%s)"

# login no auth → access token (com accountId, roles e aud do payments)
ACCESS=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -H "X-Correlation-Id: $CID" \
  -d '{"email":"alice@platinumcoin.dev","password":"Seed@12345"}' | jq -r .accessToken)

# Pix no payments com o mesmo correlationId → comprovante com o accountId DO TOKEN
curl -s -X POST localhost:8082/v1/pix -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' -H "X-Correlation-Id: $CID" \
  -d '{"pixKey":"bob@banco.dev","amount":42.50}' | jq

# regra de ouro: accountId divergente no corpo → 422 ACCOUNT_MISMATCH
curl -si -X POST localhost:8082/v1/pix -H "Authorization: Bearer $ACCESS" \
  -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@banco.dev","amount":42.50,"accountId":"00000000-0000-0000-0000-000000000000"}'

# sem token → 401 problem+json; o mesmo $CID aparece nos logs JSON dos DOIS serviços
curl -si -X POST localhost:8082/v1/pix -H 'Content-Type: application/json' \
  -d '{"pixKey":"bob@banco.dev","amount":1}'
```

## RBAC: customer vs. support (Fatia 4)

Autorização por realm role, resolvida estruturalmente no `SecurityConfig` (não por `if` no
handler): `/v1/pix` exige `customer`, `/v1/admin/**` exige `support`. Token **válido** com a
role errada → **403** (`ACCESS_DENIED`); token inválido/ausente/aud errado continua **401**.

```bash
# carol tem a role support (seed do Terraform) — autentica, mas não envia Pix
SUPPORT=$(curl -s -X POST localhost:8081/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"carol@platinumcoin.dev","password":"Seed@12345"}' | jq -r .accessToken)

curl -si -X POST localhost:8082/v1/pix -H "Authorization: Bearer $SUPPORT" \
  -H 'Content-Type: application/json' -d '{"pixKey":"bob@banco.dev","amount":1}'   # → 403

curl -s localhost:8082/v1/admin/receipts -H "Authorization: Bearer $SUPPORT" | jq  # → 200

curl -si localhost:8082/v1/admin/receipts -H "Authorization: Bearer $ACCESS"       # customer → 403
```

## Ciclo de sessão e TTLs (racional — Fatia 2)

- **Access token: 5 min.** A validação por JWKS é offline — não existe revogação imediata de um
  access token já emitido. O TTL curto **é** o mecanismo de contenção: comprometeu, vale no máximo
  5 minutos. É o trade-off consciente de não usar introspection (ver ADR-005, Fatia 3).
- **Refresh token: 30 min de inatividade, teto de 10 h** (sessão SSO do realm). Quem usa a conta
  continuamente renova sem re-login; sessão abandonada morre em 30 min.
- **Rotação de refresh com detecção de reuso** (`revoke_refresh_token`, `max_reuse = 0`): cada
  refresh emite um token novo e invalida o anterior. Um refresh token **vazado e reusado** derruba
  a sessão — o ladrão e a vítima ficam com tokens inválidos, e o dano pára no TTL do access.
- **Logout revoga a sessão** no Keycloak: refresh subsequente falha na hora. Access tokens já
  emitidos permanecem válidos até expirar (consequência stateless aceita e documentada).
