# Política de Segurança

## Escopo

Este é um **projeto de portfólio / prova de conceito**. Ele demonstra padrões corretos de
segurança de identidade (RS256/JWKS, validação de `aud`, RBAC, rotação de refresh token,
anti-enumeração), mas **não deve ser usado em produção como está**. Limitações assumidas e
documentadas:

- Credenciais de dev e usuários de seed com senhas conhecidas (Terraform).
- Direct Access Grants habilitado num client de harness — postura de produção é
  authorization code + PKCE (ADR-008).
- Idempotência e comprovantes em memória, sem persistência (ADR-010).
- SMTP local (Mailhog) — nenhum e-mail sai da máquina.
- TLS não configurado nos serviços locais.

## Reportando uma vulnerabilidade

Se você encontrar uma falha que invalide a **tese do projeto** (ex.: aceitar token de outra
audiência, debitar um `accountId` do corpo da requisição, bypass de role), isso é um bug de
verdade — abra uma issue no GitHub descrevendo o cenário, de preferência com um curl
reproduzível.

Para qualquer coisa sensível que não deva ser pública, use o
[report privado de vulnerabilidade do GitHub](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
neste repositório.

## Princípios que o código segue

- Serviços downstream **nunca** têm credencial do IdP — confiança via chave pública (JWKS).
- O valor debitado vem **sempre** do claim `accountId` do token, nunca do corpo.
- Respostas de erro nunca vazam stack trace nem existência de conta (202 anti-enumeração).
- Tokens nunca aparecem em log.
