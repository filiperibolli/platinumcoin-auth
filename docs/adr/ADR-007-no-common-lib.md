# ADR-007 — Sem common-lib entre serviços (duplicação proposital)

**Status:** aceito — Fatia 3 (2026-07)

## Contexto

`auth` e `payments` têm código quase idêntico de cross-cutting: config de resource server,
entry point 401 em problem+json, filtro de correlationId, logback JSON. O reflexo natural é
extrair uma `platinumcoin-common`.

## Decisão

**Não há biblioteca compartilhada.** Cada serviço tem `pom.xml` próprio, build próprio e cópia
própria da config de validação.

## Consequências

- **A tese fica visível**: a confiança do payments no IdP cabe em duas URLs públicas no
  `application.yml` dele. Com uma common-lib, o revisor teria que auditar a lib para saber se
  algum segredo ou cliente admin vazou para o consumidor.
- **Sem acoplamento de release**: um bug na validação de um serviço não força redeploy do outro;
  não nasce o "trem de release" da lib compartilhada — em fintech, lib de segurança compartilhada
  vira dependência crítica com dono difuso.
- A validação é **IdP-agnóstica** em cada serviço: trocar Keycloak por Cognito muda config, não
  contrato entre serviços.
- Custo real: drift entre as cópias (ex.: corrigir o entry point num serviço e esquecer o outro).
  Aceito porque são ~4 arquivos pequenos e 2 serviços; com N serviços ou lógica de validação
  crescendo, a decisão deve ser revisitada — o limiar está documentado aqui de propósito.
