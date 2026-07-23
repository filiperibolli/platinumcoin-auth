# ADR-010 — Idempotência do `POST /v1/pix` via `Idempotency-Key`

**Status:** aceito — Fatia 6 (2026-07)

## Contexto

Retry é inevitável em pagamento: o cliente deu timeout, não sabe se o Pix executou, e reenvia.
Sem proteção, o reenvio debita duas vezes. A fatia final adiciona a proteção clássica: o cliente
manda um header **`Idempotency-Key`** (um UUID que ele gera por operação) e o serviço garante
que a mesma operação executa **no máximo uma vez**.

## Decisão

Dedup **in-memory com TTL** no próprio `PixService` (POC — o Pix já é mock e os comprovantes já
vivem em memória). Regras:

- **Key escopada por conta**: o índice de dedup é `(accountId do token, Idempotency-Key)`.
  A key de um cliente nunca colide com a de outro — e não vira canal para ler comprovante
  alheio, porque a conta continua vindo **do token, nunca do corpo** (regra de ouro, ADR-006).
- **Replay honesto** (mesma key + mesmo payload) → **mesma resposta** (201 com o mesmo
  comprovante), **sem segundo débito**. O valor é normalizado (`42.50` ≡ `42.5`): escala de
  BigDecimal não transforma retry em conflito.
- **Reuso indevido** (mesma key + payload diferente) → **409 `IDEMPOTENCY_CONFLICT`**. Não é
  retry, é outra operação tentando pegar carona na key — rejeitar é mais seguro que executar.
- **Corrida**: o débito acontece dentro do `computeIfAbsent` do `ConcurrentHashMap` — atômico
  por key, dois reenvios simultâneos debitam uma vez só.
- **TTL (10 min, configurável)**: a janela cobre o retry por timeout; não é histórico de
  transação (o comprovante não some — só a proteção de replay expira). Expiração preguiçosa
  a cada uso, sem thread de limpeza.
- **Sem key** → sem dedup: o header é opt-in do cliente, como nos gateways de pagamento reais
  (Stripe et al.).

## Consequências

- Em produção o dedup seria **compartilhado e durável** (Redis com TTL, ou tabela com unique
  constraint na key) — in-memory não sobrevive a restart nem a mais de uma instância. O corte
  é consciente e está sinalizado no código e no `docs/dod.md`.
- Guardamos a resposta inteira (o comprovante), não só um marcador: replay devolve o resultado
  original, que é o contrato esperado por quem fez retry.
- A key fica fora do log (pode carregar semântica do cliente); o `receiptId` + `correlationId`
  já amarram a auditoria.
