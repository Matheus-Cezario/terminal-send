# terminal-send

Mensageiro 1:1 pelo terminal, com criptografia ponta a ponta e histórico salvo **só localmente**.

- Login por email + senha, com verificação de email por código
- Convites por email ou ID curto (`ts-7KQ2MX`); as mensagens só começam depois do aceite
- Servidor Spring Boot que apenas retransmite envelopes cifrados (store-and-forward até o ACK)
- Cliente TUI em Java (Lanterna) com histórico em SQLite

## Documentação

- [Tech-spec](docs/tech-spec.md)
- [Arquitetura](docs/architecture.md)

## Rodando localmente

Requisitos: JDK 17+ para rodar o Gradle (o toolchain baixa o Java 21 sozinho) e Docker.

```bash
docker compose up -d                       # Postgres + Mailpit (emails em http://localhost:8025)
./gradlew :server:bootRun                  # API em http://localhost:8080
./gradlew :client:shadowJar
java -jar client/build/libs/terminal-send.jar --server http://localhost:8080
```

Para rodar os testes, use `./gradlew build`. Os testes do servidor sobem o Postgres via Testcontainers.

## Estrutura

| Módulo | Conteúdo |
|--------|----------|
| `protocol/` | DTOs REST e frames WebSocket compartilhados |
| `server/` | Spring Boot 4: auth, conexões, relay WS, Flyway |
| `client/` | App de terminal: Lanterna, SQLite, criptografia E2E |

## Status

🚧 Em desenvolvimento — plano de milestones na tech-spec (§10).

- [x] M0 — Tech-spec e arquitetura
- [x] M1 — Esqueleto multi-módulo, docker-compose, schema Flyway, CI
- [x] M2 — Auth: cadastro, verificação de email, login, refresh e logout
- [x] M3 — Conexões (convite / aceite / remoção)
- [x] M4 — Criptografia E2E no cliente + publicação de chave
- [x] M5 — Relay WebSocket + store-and-forward
- [ ] M6 — Cliente TUI completo
- [ ] M7 — Hardening e empacotamento
