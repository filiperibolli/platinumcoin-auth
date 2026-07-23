# ADR-006 — Validação de `aud` entre serviços

**Status:** aceito — Fatia 3 (2026-07)

## Contexto

Todos os tokens do realm são assinados pela mesma chave. Sem checagem de audiência, um token
válido emitido para **qualquer** serviço seria aceito por **todos** — um serviço comprometido
(ou um cliente malicioso) poderia repassar tokens legítimos para onde eles nunca foram
destinados (confused deputy / replay lateral).

## Decisão

Cada serviço **exige o seu próprio `aud`**. O Terraform cria o client scope `payments` com um
**audience mapper** que carimba `aud: platinumcoin-payments` no access token; o payments soma um
`AudienceValidator` custom aos validadores default do resource server e recusa com **401**
qualquer token sem essa audiência — mesmo que a assinatura, o issuer e a expiração estejam
perfeitos.

A audiência é uma **string custom** (não um client registrado no Keycloak): o payments é só
consumidor, não participa de nenhum fluxo OIDC, então registrar um client seria cerimônia sem
função.

## Consequências

- Token do harness (que carrega o scope `payments`) funciona no payments; token de qualquer
  outro client/scope do mesmo realm → 401. Coberto por teste de integração.
- O 401 de `aud` errado é **indistinguível** dos demais 401 (fail-closed): a resposta não
  revela qual verificação falhou.
- Novos consumidores repetem o padrão: scope próprio + mapper próprio + validador próprio.
  A duplicação é proposital (ADR-007).
- Escopo do token cresce um claim; irrelevante.
