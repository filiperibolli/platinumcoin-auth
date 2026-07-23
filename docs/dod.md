# Definition of Done — por fatia

> Critério de **aceite** (o portão de "pronto") de cada fatia do `PLAN.md`. Uma fatia só está
> concluída quando todos os itens abaixo estão marcáveis e a verificação roda na mão.
>
> **Calibragem deste projeto (fintech, mas POC local):**
> - **Segurança = grau de produção** — validação RS256/JWKS, `aud`, fail-closed, regra de ouro do
>   Pix, rotação de refresh. É o que o projeto existe para provar; aqui não se corta.
> - **Negócio e infra = POC local** — Pix é mock (não move dinheiro), dedup in-memory, e-mail via
>   Mailhog, Keycloak+Postgres em Docker sem HA. Cada corte é **explícito** (ver "Fora de escopo",
>   no fim), para o revisor ver decisão consciente e não esquecimento.
> - **Cada decisão de peso vira um ADR curto** em `docs/adr/`, escrito na fatia a que pertence.

---

## Fatia 1 — Login e identidade confiável *(núcleo da tese)*

- [ ] `docker-compose up -d` sobe **Keycloak 26.6 + Postgres** na mesma rede; serviços saudáveis.
- [ ] `terraform apply` é **idempotente** e provisiona o realm `platinumcoin`: client de harness com
      **Direct Access Grants ON**, client scope `account` (default) com **User Attribute mapper**
      `accountId` e **Add to access token = ON**, `accountId` **declarado no realm user profile**, e
      ≥1 usuário de seed.
- [ ] `platinumcoin-auth` (single-module, `api/domain/infra`) sobe na **8081**.
- [ ] `POST /v1/auth/register` cria o usuário via **Admin API**, gera e grava `accountId` (UUID) como
      atributo; retorna **201** com a identidade (nunca devolve senha nem token).
- [ ] `POST /v1/auth/login` troca usuário/senha por **access + refresh** (+ expiração).
- [ ] O **access token** decodificado contém o claim **`accountId`**.
- [ ] `GET /v1/me` valida RS256 via **JWKS** e devolve `sub`/email/`accountId`. Token
      ausente/malformado/expirado/mal-assinado → **401** `application/problem+json` com `code` e
      `correlationId`, **sem vazar qual verificação falhou** (fail-closed).
- [ ] Filtro de **`correlationId`** (lê/gera `X-Correlation-Id` → MDC); **log JSON**; **token nunca
      logado**; Actuator `/health` **UP**.
- [ ] **1 teste de integração** (Testcontainers Keycloak): login → `/v1/me` feliz **e** um caminho 401.
- [ ] **ADR-002** (RS256 + JWKS) e **ADR-003** (accountId SoT no Keycloak) escritos.

## Fatia 2 — Ciclo de sessão: refresh + logout/revogação

- [ ] `POST /v1/auth/refresh` troca refresh por novo par; **rotação de refresh** ativa.
- [ ] Refresh **reusado** após rotação → **falha** (401/invalid_grant).
- [ ] `POST /v1/auth/logout` encerra a sessão (`end_session`/revoke); refresh subsequente → **401**.
- [ ] **TTLs** configurados no realm via Terraform: access curto (~5 min), refresh maior.
- [ ] **IT**: refresh feliz + refresh reusado falha + logout invalida.
- [ ] Racional de TTL/revogação documentado (nota no ADR-005 ou README).

## Fatia 3 — platinumcoin-payments *(prova a assimetria + a regra de ouro)*

- [ ] `downstream/payments` (single-module) sobe na **8082**, **sem credencial de admin nem segredo**
      do Keycloak — só `issuer-uri`/`jwk-set-uri` na config (assimetria **visível** ao revisor).
- [ ] Resource server valida **RS256 via JWKS** + **validador de `aud` custom** (só aceita o `aud`
      destinado ao payments) + `iss`/`exp`/`nbf` com tolerância de clock-skew.
- [ ] `POST /v1/pix` exige role/scope **`customer`**; **debita o `accountId` do token**; `accountId`
      no **corpo é ignorado/rejeitado** com erro claro; devolve comprovante mock (id + status).
- [ ] O `correlationId` recebido no header é **propagado** e aparece nos logs do payments; no fluxo
      E2E o **mesmo id** aparece nos logs dos **dois** serviços.
- [ ] Terraform: role `customer`, client scope/`aud` do payments, mapper de audiência.
- [ ] **Testes**: (a) token válido → Pix com `accountId` do token; (b) **401** sem/expirado/
      mal-assinado/`aud` errado; (c) **regra de ouro** (body divergente → token prevalece/rejeita);
      (d) **token forjado** (assinado por outra chave) → 401 via JWKS.
- [ ] **ADR-005** (JWKS local vs. introspection), **ADR-006** (`aud`), **ADR-007** (sem common-lib).

## Fatia 4 — Autorização RBAC (customer vs. support) + testes negativos

- [ ] Role **`support`** existe e **não** tem `pix:send`; endpoint administrativo protegido por role.
- [ ] Mapeamento de roles do Keycloak → authorities do Spring (`JwtAuthenticationConverter`).
- [ ] **Testes**: token `support` no `/v1/pix` → **403**; token de outra audiência → 401; token
      forjado → 401.
- [ ] *(Pode fundir com a Fatia 3 se ficar enxuto; se separada, vale este DoD.)*

## Fatia 5 — Fluxos de conta de fintech

- [x] **Mailhog** no compose (SMTP dev; UI em 8025).
- [x] Confirmação de e-mail no cadastro (ou `POST /v1/auth/verify-email`) → e-mail **chega no
      Mailhog**; **reenvio** de código funciona.
- [x] `POST /v1/auth/forgot-password` → e-mail de reset e `POST /v1/auth/change-password`
      (re-autentica + revoga sessões) — delegados às ações nativas do Keycloak. *Adaptação
      consciente:* não há `POST /v1/auth/reset-password` no BFF — o action token do e-mail é
      consumido pelo próprio Keycloak (ADR-009); um endpoint nosso exigiria reimplementar
      emissão de prova de identidade fora do IdP.
- [x] Pelo menos o fluxo de **reset** verificado (IT com Keycloak + Mailhog na mesma network:
      `AccountFlowIntegrationTests`) e runbook com curl no README.

## Fatia 6 — MFA (TOTP) *(opcional)*

- [ ] Realm configura **TOTP** como required action; enrollment gera o segredo/QR.
- [ ] Com TOTP ativo, o login **exige o código**; runbook mostra enroll + login.
- [ ] Se inflar o escopo/testes: **documentar no README** ("MFA nativo do Keycloak, ativável")
      em vez de construir. Decisão registrada.

## Fatia 7 — Idempotência no `/v1/pix` *(opcional, final)*

- [ ] Header **`Idempotency-Key`**; dedup **in-memory** (com TTL).
- [ ] Mesma key + mesmo payload → **mesma resposta, sem segundo débito**; mesma key + payload
      diferente → **409**.
- [ ] **Teste**: reenvio com a mesma key não duplica o débito.
- [ ] Fallback aceitável se o tempo apertar: **parágrafo no README** ("em produção teria
      idempotência"), sinalizando a consciência do problema.

---

## Fora de escopo (cortes conscientes desta POC)

Deixado de fora **de propósito** — em produção entraria, mas não agrega à tese e infla a POC local:

- HA/cluster de Keycloak, secrets manager, TLS/mTLS entre serviços, WAF.
- Rate limiting, bloqueio de brute-force além do padrão do Keycloak, CAPTCHA.
- Provedor de e-mail real (usamos Mailhog), verificação de telefone/KYC.
- Persistência real de pagamentos, ledger, conciliação — o Pix é **mock de negócio**.
- Métricas/dashboards/tracing distribuído — só Actuator health + propagação de `correlationId`.
- Cobertura de teste exaustiva — apenas os **fluxos críticos** que provam a tese.
