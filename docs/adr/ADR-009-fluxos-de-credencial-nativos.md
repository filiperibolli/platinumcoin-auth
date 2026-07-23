# ADR-009 — Fluxos de credencial delegados às ações nativas do Keycloak

**Status:** aceito — Fatia 5 (2026-07)

## Contexto

A fatia pede verificação de e-mail, esqueci-minha-senha e troca de senha. Há duas formas de
construir isso num BFF:

1. **Fluxo próprio**: o auth-service gera código/token de reset, envia o e-mail, guarda estado
   (código, expiração, tentativas) e expõe `POST /v1/auth/reset-password` que consome o código.
2. **Ações nativas do IdP**: o auth-service só dispara `send-verify-email` /
   `execute-actions-email(UPDATE_PASSWORD)` pela Admin API; o link do e-mail carrega um **action
   token assinado pelo próprio Keycloak** e o fluxo conclui na página dele.

## Decisão

**Ações nativas.** O auth-service dispara os e-mails, mas nunca emite, guarda ou valida prova de
reset — quem é dono de credencial é o IdP (mesma lógica do ADR-003: o serviço é fachada stateless).

- `POST /v1/auth/verify-email` e `POST /v1/auth/forgot-password` respondem **202 sempre**,
  exista o e-mail ou não: a resposta não pode ser oráculo de quais e-mails têm conta
  (anti-enumeração), coerente com o 401 genérico do login.
- **Não existe `POST /v1/auth/reset-password`** no BFF: o action token do e-mail é opaco para
  nós por design — só o Keycloak o valida/consome. Um endpoint nosso exigiria o fluxo próprio
  (opção 1), reimplementando emissão de prova de identidade fora do IdP — exatamente o que a
  tese do projeto proíbe.
- `POST /v1/auth/change-password` (autenticado) **re-autentica com a senha atual** antes de
  trocar: o Bearer token prova a sessão, não a posse da senha — um token vazado não basta para
  tomar a conta. Após a troca, as **sessões ativas são revogadas** (`logout-all`): refresh
  tokens antigos morrem na hora; access tokens já emitidos valem até o `exp` (~5 min, ADR-005).
- O e-mail do change-password vem **do token, nunca do corpo** — mesma regra de ouro do Pix.

## Consequências

- Zero estado novo no auth-service; e-mails de template do Keycloak (customizáveis por tema,
  fora do escopo da POC).
- A UX do reset passa pela página do Keycloak — aceitável aqui; num produto, tema custom ou
  fluxo próprio seriam o próximo passo, e o custo de segurança dessa troca ficaria explícito.
- Cadastro nasce `emailVerified=false` e dispara a verificação **best-effort**: SMTP fora do ar
  não desfaz um cadastro que já existe no IdP (o reenvio cobre depois). O realm **não bloqueia
  login sem e-mail verificado** — corte consciente da POC; em produção `verify_email=true` no
  realm fecharia a porta.
- Dev/teste usam **Mailhog** (compose e Testcontainers na mesma network do Keycloak); nenhum
  e-mail sai da máquina.
