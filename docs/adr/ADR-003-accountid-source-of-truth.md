# ADR-003 — `accountId`: nasce no registro, vive como user attribute no Keycloak

**Status:** aceito — Fatia 1 (2026-07)

## Contexto

Todo débito Pix precisa de um identificador de conta **confiável** — que o cliente não possa
escolher nem alterar. A pergunta é onde esse `accountId` nasce e onde é a fonte da verdade,
sabendo que o auth-service deve ser **stateless** (sem banco próprio).

## Decisão

- O `accountId` (UUID) **nasce no domínio do auth-service** durante o `POST /v1/auth/register`
  e é gravado como **user attribute no Keycloak** via Admin API — o Keycloak é a fonte da verdade.
- O atributo entra no **access token** como claim `accountId` via User Attribute mapper no client
  scope `account` (com *Add to access token = ON* — gotcha do Keycloak 26.x, que também exige o
  atributo **declarado no Declarative User Profile**, senão é ignorado em silêncio).
- No user profile, `accountId` tem **edição restrita a admin**: o próprio usuário não consegue
  alterá-lo por nenhuma API de self-service do Keycloak.
- Downstream (payments) **debita o `accountId` do token, nunca do corpo** — a regra de ouro.

## Alternativas descartadas

- **Banco próprio no auth-service** mapeando `sub → accountId`: cria estado, réplica e mais um
  ponto de falha; o atributo no IdP dá o mesmo resultado sem estado.
- **Usar o `sub` como accountId**: acopla o identificador de negócio ao identificador técnico do
  IdP; migrar de IdP (ex.: Cognito) mudaria o `sub`, mas o `accountId` sobrevive como atributo.

## Consequências

- Auth-service permanece stateless; restaurar/migrar contas = migrar usuários do Keycloak.
- O serviço que registra precisa de credencial de Admin API — isolada num **service account
  dedicado** (`auth-service-admin`) com apenas `manage-users`/`view-users` do realm, nunca o
  admin do master.
- Evolução: se o `accountId` ganhar ciclo de vida próprio (múltiplas contas por usuário, KYC),
  promove-se a um serviço de contas com banco próprio; o claim no token continua igual para os
  consumidores.
