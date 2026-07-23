# ADR-005 — Validação no consumidor: JWKS local (vs. introspection no IdP)

**Status:** aceito — Fatia 3 (2026-07)

## Contexto

O `payments` precisa decidir, a cada requisição, se o token é válido. Duas rotas:

1. **Validação local via JWKS**: verifica assinatura/claims com a chave pública do realm,
   cacheada. Nenhuma chamada ao IdP no caminho quente.
2. **Token introspection (RFC 7662)**: pergunta ao IdP "este token está ativo?" a cada request.
   Dá revogação imediata, mas acopla disponibilidade e latência do payments ao IdP — e exige
   credencial de client no consumidor, quebrando a assimetria que a tese quer provar.

## Decisão

**JWKS local** no payments. A config inteira de confiança são duas URLs públicas
(`issuer-uri` + `jwk-set-uri`); nenhum segredo, nenhuma credencial, nenhuma chamada ao
Keycloak em runtime além do fetch cacheado do JWKS.

## Consequências

- **Sem revogação imediata de access token**: um token emitido vale até o `exp`, mesmo após
  logout. A contenção é o **TTL curto de 5 min** (Fatia 2) — comprometeu, vale no máximo
  5 minutos. Trade-off consciente, documentado no README ("Ciclo de sessão e TTLs").
- Payments continua validando mesmo com o Keycloak fora do ar (só não emite tokens novos):
  falha de IdP degrada emissão, não autorização.
- Introspection fica documentada como alternativa para operações de altíssimo risco
  (ex.: transferências acima de um limiar poderiam re-checar a sessão) — não implementada
  nesta POC.
- Exige disciplina nos validadores locais: assinatura (JWKS), `iss`, `exp`/`nbf` com
  clock-skew de 60s e `aud` (ADR-006) — todos fail-closed no mesmo 401.
