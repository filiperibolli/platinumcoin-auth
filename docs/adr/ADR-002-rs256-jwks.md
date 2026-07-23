# ADR-002 — RS256 + validação via JWKS (vs. HS256 + segredo compartilhado)

**Status:** aceito — Fatia 1 (2026-07)

## Contexto

Os serviços downstream (payments, e o próprio `/v1/me` do auth) precisam validar tokens emitidos
pelo IdP. Com HS256 a assinatura usa um segredo simétrico: **quem valida também consegue emitir**.
Numa fintech isso significa que qualquer serviço comprometido vira uma fábrica de tokens.

## Decisão

Tokens assinados com **RS256** (par de chaves do realm no Keycloak). Consumidores validam a
assinatura com a **chave pública publicada via JWKS**
(`/realms/platinumcoin/protocol/openid-connect/certs`), resolvida pelo Spring Security Resource
Server a partir do `issuer-uri`.

## Consequências

- **Assimetria de confiança**: downstream valida mas não emite. Um serviço comprometido não
  consegue forjar tokens — é o núcleo da tese do projeto.
- Nenhum segredo de assinatura distribuído; a config do consumidor é só `issuer-uri` (público).
- **Rotação de chaves** é transparente: o JWKS publica o `kid` novo e o resource server
  re-resolve; nenhum deploy nos consumidores.
- Custo: validação exige fetch (cacheado) do JWKS; tokens RS256 são maiores que HS256.
  Irrelevante para o cenário.
- Token **forjado** (assinado por outra chave) falha na verificação de assinatura → 401.
  Coberto por teste negativo na Fatia 3/4.
