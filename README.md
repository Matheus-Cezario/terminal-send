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

Na interface: crie a conta, digite o código que chegou no Mailpit e use `/add <email|ts-ID>` para convidar
alguém. `Tab` alterna entre a lista de contatos e o campo de texto, e `/help` lista os comandos.

Para ver dois clientes conversando sem abrir dois terminais: `pip install pyte pexpect && python3 scripts/tui-demo.py`.

Para rodar os testes, use `./gradlew build`. Os testes do servidor sobem o Postgres via Testcontainers.

## Deploy

O servidor roda como container com o perfil `prod`, atrás de um proxy que termina o TLS (Caddy, nginx, Traefik).

```bash
./gradlew :server:bootJar
docker build -t terminal-send-server .
docker run -p 8080:8080 \
  -e TS_DB_URL=jdbc:postgresql://db:5432/terminal_send -e TS_DB_USER=... -e TS_DB_PASSWORD=... \
  -e TS_JWT_SECRET="$(openssl rand -base64 48)" \
  -e TS_MAIL_HOST=smtp.seuprovedor.com -e TS_MAIL_USER=... -e TS_MAIL_PASSWORD=... \
  -e TS_MAIL_FROM="terminal-send <no-reply@seu-dominio>" \
  terminal-send-server
```

O servidor se recusa a subir sem `TS_JWT_SECRET` ou com o segredo de dev. Para testar a stack inteira em
containers localmente: `docker compose --profile full up -d --build`.

Releases: ao criar uma tag `v*`, o GitHub Actions publica `terminal-send.jar` (cliente) e `server.jar` na
release, e a imagem em `ghcr.io/<owner>/terminal-send-server`.

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
- [x] M6 — Cliente TUI completo
- [x] M7 — Hardening e empacotamento
