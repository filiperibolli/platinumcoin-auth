# platinumcoin-auth

Serviço de identidade/autenticação de uma fintech de pagamentos instantâneos (Pix) — projeto de
portfólio. Tese: um IdP real (**Keycloak**) emite **JWT RS256** com um claim `accountId`; serviços
downstream **confiam sem poder emitir** — validam via **JWKS**, exigem o `aud` deles e **debitam o
`accountId` do token, nunca do corpo**.

Roadmap em [`PLAN.md`](PLAN.md), critérios de aceite em [`docs/dod.md`](docs/dod.md), decisões em
[`docs/adr/`](docs/adr/).

## Subir

```bash
docker-compose up -d                          # Keycloak 26.6 (8080) + Postgres
(cd terraform && terraform init && terraform apply)   # realm platinumcoin
mvn spring-boot:run                           # auth-service na 8081
```

## Endpoints (`/v1`)

| Endpoint | O quê |
|---|---|
| `POST /v1/auth/register` | Cria usuário via Admin API (service account de menor privilégio); gera `accountId` |
| `POST /v1/auth/login` | Direct grant (harness) → access + refresh |
| `POST /v1/auth/refresh` | Troca refresh por novo par (rotação ativa) |
| `POST /v1/auth/logout` | Revoga a sessão no IdP |
| `GET /v1/me` | Identidade extraída do token (RS256 via JWKS) |

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
