# terminal-send — Arquitetura

Complementa a [tech-spec](tech-spec.md). Os diagramas usam Mermaid e são renderizados pelo GitHub.

## 1. Contexto

```mermaid
flowchart LR
    A[Usuário A<br/>terminal] -- HTTPS / WSS --> S[(terminal-send server)]
    B[Usuário B<br/>terminal] -- HTTPS / WSS --> S
    S -- SMTP --> M[Provedor de email<br/>Mailpit em dev]
    S --- P[(PostgreSQL)]
    A --- LA[(SQLite local A)]
    B --- LB[(SQLite local B)]
```

- O **servidor** é um relay autenticado: guarda usuários, conexões, chaves públicas e
  envelopes **ainda não entregues**, mas nunca o texto das mensagens.
- Cada **cliente** é dono do seu histórico (SQLite) e da sua chave privada.

## 2. Módulos (monorepo Gradle)

```
terminal-send/
├── protocol/   # Java puro: DTOs REST, frames WS, validação de handle/email. Sem Spring.
├── server/     # Spring Boot 4: API REST, WebSocket, JPA, Flyway, Mail.
├── client/     # App de terminal: Lanterna, HttpClient/WebSocket, SQLite, cripto.
├── docs/
└── docker-compose.yml   # postgres + mailpit para dev
```

```mermaid
flowchart TB
    protocol
    server --> protocol
    client --> protocol
```

O `protocol` é o **contrato** compartilhado: qualquer mudança nele quebra a compilação
de quem não acompanhou, e isso evita divergência de JSON entre cliente e servidor.

## 3. Servidor

Organização **por feature** (package-by-feature), com camadas dentro de cada uma:

```
dev.terminalsend.server
├── auth/           AuthController, AuthService, JwtService, RefreshTokenRepository
├── verification/   EmailVerificationService, VerificationCodeRepository
├── mail/           MailSender (port) + SmtpMailSender (adapter) + templates
├── user/           User (entity), UserRepository, MeController, HandleGenerator
├── key/            KeyController, KeyService
├── connection/     ConnectionController, ConnectionService, Connection (entity)
├── messaging/      RelayService, PendingMessageRepository, PresenceRegistry
├── ws/             WsConfig, WsAuthInterceptor, WsHandler (JSON ⇄ frames)
├── config/         SecurityConfig, ClockConfig, properties
└── common/         ProblemDetails, ApiException, ErrorCode
```

```mermaid
flowchart LR
    subgraph Web
        RC[REST Controllers]
        WH[WsHandler]
    end
    subgraph Domínio
        AS[AuthService]
        CS[ConnectionService]
        KS[KeyService]
        RS[RelayService]
        PR[PresenceRegistry<br/>userId → WebSocketSession]
    end
    subgraph Infra
        JPA[(Repositórios JPA)]
        MS[MailSender]
    end
    RC --> AS & CS & KS
    WH --> RS
    RS --> PR
    CS -- eventos --> PR
    AS & CS & KS & RS --> JPA
    AS --> MS
```

**Eventos internos**: `ConnectionService` e `KeyService` publicam `ApplicationEvent`s
(`ConnectionRequested`, `ConnectionAccepted`, `KeyChanged`). Um listener com
`@TransactionalEventListener(AFTER_COMMIT)` transforma esses eventos em frames WS, para
que nenhuma notificação saia de uma transação revertida.

**PresenceRegistry**: um `ConcurrentHashMap<UUID, WebSocketSession>`. Os envios usam
`ConcurrentWebSocketSessionDecorator`, porque o `WebSocketSession` não é thread-safe. Para
escalar horizontalmente, essa interface ganha uma implementação com Redis.

**Segurança**: Spring Security stateless. `oauth2-resource-server` valida o JWT HS256
(`NimbusJwtDecoder` com segredo de env). O endpoint `/ws` usa o mesmo decoder num
`HandshakeInterceptor`.

## 4. Cliente

Arquitetura em camadas, com a UI isolada atrás de interfaces:

```
dev.terminalsend.client
├── Main                     # bootstrap, lê config, sobe a UI
├── ui/                      # Lanterna: LoginWindow, VerifyWindow, MainWindow (contatos | chat)
├── app/                     # casos de uso: AuthService, ContactService, ChatService
├── net/                     # ApiClient (HttpClient), RealtimeClient (WebSocket + reconexão)
├── crypto/                  # IdentityKeys, PairKeyDeriver (HKDF), EnvelopeCipher, KeyFileStore
├── store/                   # LocalStore (SQLite/JDBC), migrations, Outbox
└── event/                   # EventBus simples (eventos do WS → UI)
```

```mermaid
flowchart TB
    UI[ui: Lanterna] --> APP[app: casos de uso]
    APP --> NET[net: REST + WS]
    APP --> CRY[crypto]
    APP --> ST[store: SQLite]
    NET -- eventos --> BUS[event bus] --> APP
    APP -- atualiza --> UI
```

- **Threads**: a thread de UI do Lanterna só desenha. O WebSocket entrega callbacks em
  threads do `HttpClient`, que publicam no `EventBus`. O `app` processa (decifra e grava)
  e devolve atualizações para a UI via `gui.getGUIThread().invokeLater(...)`.
- **Reconexão**: backoff exponencial com jitter (1 s → 30 s). Ao reconectar, o `Outbox` é
  drenado e o servidor reenvia os pendentes.
- **Comandos** no campo de entrada: `/add <email|ts-id>`, `/invites`, `/accept <n>`,
  `/reject <n>`, `/verify <contato>`, `/clear`, `/logout`, `/quit`.

### Esboço da TUI

```
┌ Contatos ──────────┬ ana@exemplo.com (ts-7KQ2MX) ───────────────────┐
│ ● ana       (2)    │ [12:01] ana: oi!                               │
│ ○ bruno            │ [12:02] você: e aí, tudo certo?            ✓✓ │
│ ─ Convites (1) ─   │                                                │
│ ? carla@x.com      │                                                │
│                    ├────────────────────────────────────────────────┤
│                    │ > _                                            │
└────────────────────┴ online · fp 3f2a 91c0 … ── Ctrl+C sair ────────┘
```
`✓` = aceito pelo servidor · `✓✓` = entregue ao destinatário.

## 5. Fluxos

### 5.1 Cadastro e verificação

```mermaid
sequenceDiagram
    participant C as Cliente
    participant S as Server
    participant M as SMTP
    C->>S: POST /auth/register {email, senha}
    S->>S: hash Argon2id, gera handle + código
    S->>M: email com código (6 dígitos)
    S-->>C: 201 {userId, handle}
    C->>S: POST /auth/verify {email, senha, código}
    S-->>C: 200 TokenPair
    C->>C: gera par X25519, cifra a privada com a senha
    C->>S: PUT /me/key {publicKey}
```

### 5.2 Conexão

```mermaid
sequenceDiagram
    participant A as Cliente A
    participant S as Server
    participant B as Cliente B
    A->>S: POST /connections {target: "b@x.com"}
    S-->>A: 202 (mesmo se não existir)
    S-)B: WS connection.requested
    B->>S: POST /connections/{id}/accept
    S-)A: WS connection.accepted
    A->>S: GET /users/{B}/key
    B->>S: GET /users/{A}/key
```

### 5.3 Mensagem (B offline → online)

```mermaid
sequenceDiagram
    participant A as Cliente A
    participant S as Server
    participant B as Cliente B
    A->>A: grava em messages (QUEUED) + outbox
    A->>S: WS message.send {id, to, nonce, ct}
    S->>S: valida conexão + fp, INSERT pending_messages
    S-->>A: message.accepted (✓)
    Note over B: conecta depois
    B->>S: WS connect
    S-)B: message.deliver (pendentes)
    B->>B: decifra, INSERT messages
    B->>S: message.ack [ids]
    S->>S: DELETE pending_messages
    S-)A: message.delivered (✓✓)
```

Com B online, o servidor tenta a entrega direta, mas **persiste do mesmo jeito** antes do
`message.accepted`. Isso dá um único caminho de código, e o `DELETE` no ACK cobre os dois
casos.

## 6. Ambientes e deploy

| Ambiente | Server | Banco | Email |
|----------|--------|-------|-------|
| dev | `./gradlew :server:bootRun` | Postgres no docker-compose | Mailpit (UI em http://localhost:8025) |
| test | Testcontainers | Postgres efêmero | GreenMail embutido |
| prod | Imagem OCI (`bootBuildImage`) atrás de proxy TLS | Postgres gerenciado | SMTP via env |

Configuração por variáveis de ambiente: `TS_DB_URL`, `TS_DB_USER`, `TS_DB_PASSWORD`,
`TS_JWT_SECRET`, `TS_MAIL_HOST`, `TS_MAIL_PORT`, `TS_MAIL_USER`, `TS_MAIL_PASSWORD`,
`TS_MAIL_FROM`.

O cliente é distribuído como fat-jar (`./gradlew :client:shadowJar`), com o servidor
configurável em `~/.terminal-send/config.properties` ou via `--server`.
