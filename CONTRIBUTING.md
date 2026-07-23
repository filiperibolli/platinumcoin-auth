# Contribuindo

Este é um projeto de portfólio, mas segue processo de repositório de produção. Se quiser
propor algo, o caminho é o mesmo usado no desenvolvimento:

## Processo

1. **Leia [`PLAN.md`](PLAN.md)** — o roadmap é em fatias verticais; cada fatia entrega um fluxo
   utilizável de ponta a ponta. O critério de aceite de cada uma está em
   [`docs/dod.md`](docs/dod.md).
2. **Branch + PR.** Nada vai direto para `main`. Commits no padrão
   [Conventional Commits](https://www.conventionalcommits.org/) (`feat(fatia-N): ...`,
   `docs: ...`, `chore: ...`).
3. **Decisão de peso → ADR.** Se a mudança carrega um trade-off arquitetural, registre em
   [`docs/adr/`](docs/adr/) dentro do próprio PR (formato leve: contexto, decisão, consequências).
4. **Não re-litigue decisões travadas** sem um ADR que supere o existente — a lista está no
   [`PLAN.md`](PLAN.md) e nos ADRs.

## Convenções de código

- Java 21 + Spring Boot 3 + Maven; **single-module por serviço**, sem parent pom, sem common-lib.
- Layout `api / domain / infra`; a dependência aponta para dentro (domain não importa
  Spring/servlet/HTTP).
- Erros sempre **RFC 7807** (`application/problem+json`) com `code` e `correlationId`; nunca
  stack trace na resposta.
- Log estruturado JSON via SLF4J, `correlationId` no MDC; **token nunca logado**; sem
  `System.out`.
- REST sob `/v1/...`; records para DTOs.

## Rodando localmente

```bash
docker-compose up -d
(cd terraform && terraform init && terraform apply)
mvn verify                                    # auth-service
(cd downstream/payments && mvn verify)        # payments
```

Os fluxos manuais de verificação (curl) estão no [`README.md`](README.md); a coleção Postman em
[`docs/postman/`](docs/postman/) cobre a jornada E2E com asserts.
