# terminal-send

Mensageiro 1:1 pelo terminal, com criptografia ponta a ponta e histórico salvo **só localmente**.

- Login por email + senha, com verificação de email por código
- Convites por email ou ID curto (`ts-7KQ2MX`); as mensagens só começam depois do aceite
- Servidor Spring Boot que apenas retransmite envelopes cifrados (store-and-forward até o ACK)
- Cliente TUI em Java (Lanterna) com histórico em SQLite

## Documentação

- [Tech-spec](docs/tech-spec.md)
- [Arquitetura](docs/architecture.md)

## Status

🚧 Em desenvolvimento — ver o plano de milestones na tech-spec (§10).
