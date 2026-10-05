# terminal-send — Tech Spec

> Status: **Draft v1** · Autor: Matheus Cezario · Data: 2026-10-05

## 1. Visão geral

`terminal-send` é um mensageiro 1:1 que roda inteiramente no terminal. Um backend
Java faz autenticação, gerencia conexões entre usuários e **retransmite** mensagens
cifradas de ponta a ponta. O histórico de conversas existe **somente nos clientes**:
o servidor guarda uma mensagem apenas enquanto ela não foi entregue.

### 1.1 Objetivos

- Cadastro e login com **email + senha**, com **verificação de email** obrigatória.
- Convidar outra pessoa por **email** ou **ID curto** (`ts-7KQ2MX`); só é possível
  trocar mensagens depois que o convite é **aceito**.
- Mensagens em tempo real quando ambos estão online; **store-and-forward** quando o
  destinatário está offline.
- **Criptografia ponta a ponta (E2E)**: o servidor nunca vê o texto das mensagens.
- **Histórico local** em SQLite, por conta, na máquina do usuário.
- Interface **TUI** (Lanterna): lista de contatos + painel de chat + campo de entrada.

### 1.2 Fora de escopo (v1)

- Grupos, anexos/arquivos, chamadas.
- Multi-dispositivo (uma conta = um dispositivo ativo por vez).
- Backup/sincronização de histórico entre máquinas.
- Forward secrecy por mensagem (Double Ratchet) — ver §7.4.
- Recuperação de senha (planejado para v1.1, reaproveita o fluxo de código por email).

## 2. Decisões principais

| # | Tema | Decisão | Alternativas descartadas |
|---|------|---------|--------------------------|
| D1 | Linguagem/runtime | Java 21 (toolchain Gradle) | — |
| D2 | Backend | Spring Boot 4.1 (Web MVC, WebSocket, Security, Data JPA, Mail, Validation) | Quarkus, Javalin |
| D3 | Build | Gradle (Kotlin DSL), monorepo com módulos `protocol`, `server`, `client` | Maven |
| D4 | Transporte | REST (JSON) para auth/contatos + **WebSocket com JSON puro** para tempo real | gRPC streaming, long polling, STOMP |
| D5 | Persistência servidor | PostgreSQL 17 + Flyway | H2 |
| D6 | Persistência cliente | SQLite (`~/.terminal-send/<handle>/history.db`) | JSON em arquivo |
| D7 | Offline | Servidor enfileira o envelope cifrado e **apaga ao receber ACK** (TTL 30 dias) | Só tempo real |
| D8 | E2E | X25519 + HKDF-SHA256 + AES-256-GCM, só com APIs do JDK | Sem E2E |
| D9 | Verificação de email | Código de 6 dígitos, válido por 15 min, máx. 5 tentativas | Link mágico |
| D10 | Envio de email | Spring Mail via SMTP configurável; **Mailpit** no ambiente de dev | API Resend/SendGrid |
| D11 | Identificador público | Email **ou** handle `ts-XXXXXX` (Crockford Base32, gerado) | UUID exposto, @username |
| D12 | Dispositivos | Um ativo por vez; novo login gera novo par de chaves | Multi-device |
| D13 | Auth | JWT de acesso (15 min, HS256) + refresh token opaco rotativo (30 dias) | Sessão com cookie |
| D14 | TUI | Lanterna 3.1 | JLine REPL |
| D15 | Cliente HTTP/WS | `java.net.http.HttpClient` (inclui WebSocket nativo) + Jackson | OkHttp, Tyrus |

> Observação: o Spring Boot 3.x saiu do suporte OSS; por isso a escolha é o 4.1, que
> mantém o mesmo modelo de programação.

## 3. Requisitos funcionais

### RF1 — Cadastro e verificação de email
1. `register(email, senha)` cria o usuário com `email_verified_at = null` e envia o código. Se o email já existe **não verificado**, a senha é sobrescrita e um novo código é emitido; se já existe verificado, a resposta é `409 EMAIL_ALREADY_REGISTERED`.
2. Senha: mínimo de 10 caracteres; hash com **Argon2id** (Spring Security `Argon2PasswordEncoder`).
3. O email é normalizado (trim + lowercase) e validado (formato simplificado em `protocol/Identifiers`, compartilhado com o cliente).
4. O código tem 6 dígitos (`SecureRandom`), e só o **hash** dele é persistido. Expira em 15 min, aceita 5 tentativas e o reenvio tem cooldown de 60 s.
5. `verify(email, senha, código)` exige a senha atual, para que só quem definiu a última senha consiga reivindicar a conta (evita que alguém cadastre o email de outra pessoa com uma senha própria).
6. Login de conta não verificada → `403 EMAIL_NOT_VERIFIED`, e o cliente cai na tela de código.
7. Contas não verificadas há mais de 7 dias são removidas por um job agendado.

### RF2 — Login e sessão
1. `login(email, senha)` → `{accessToken, refreshToken, user}`.
2. O refresh é rotativo: cada uso invalida o token anterior. Se um token revogado for reutilizado, toda a família daquele login é revogada (detecção de vazamento).
3. Após 5 falhas de login em 15 min, aquele email fica bloqueado por 15 min.

### RF3 — Identidade e chaves
1. No primeiro login em um dispositivo, o cliente gera um par **X25519** e publica a chave pública (`PUT /me/key`).
2. A chave privada fica em `~/.terminal-send/<handle>/identity.key`, cifrada com uma chave derivada da senha (PBKDF2-HMAC-SHA256, 600k iterações, AES-GCM), com permissão `600`.
3. Trocar de chave (login em outro dispositivo) descarta os envelopes pendentes destinados à chave antiga e emite `key.changed` para os contatos.
4. O cliente aplica **TOFU**: guarda o fingerprint (SHA-256 da chave pública, 8 grupos hex) de cada contato e avisa se ele mudar.

### RF4 — Conexões
1. `invite(alvo)`, onde `alvo` é um email ou um handle. A resposta é sempre `202 Accepted`, exista ou não o usuário (**anti-enumeração**).
2. O convidado recebe `connection.requested` em tempo real (ou na próxima vez que listar convites).
3. `accept` / `reject` / `remove`. `reject` é silencioso para quem convidou, que continua vendo o convite como `PENDING`.
4. Só existe uma conexão por par de usuários (unicidade em `(least(a,b), greatest(a,b))`).
5. A chave pública de um usuário só é servida para quem tem conexão `ACCEPTED` com ele.

### RF5 — Mensagens
1. Só é possível enviar mensagens para conexões `ACCEPTED`.
2. O cliente cifra, envia o envelope pelo WebSocket e recebe `message.accepted` (o servidor persistiu ou entregou).
3. O servidor entrega a mensagem se o destinatário estiver online; senão, ela fica em `pending_messages`.
4. O destinatário grava a mensagem no SQLite e então envia `message.ack`. O servidor **apaga** o envelope e emite `message.delivered` para o remetente.
5. A entrega é *at-least-once*: o cliente deduplica pelo `messageId` (UUIDv7 gerado pelo remetente).
6. Envelopes têm no máximo 64 KiB de ciphertext (cerca de 16k caracteres de texto) e o limite de taxa é de 20 mensagens/s por usuário.
7. Enviar estando offline funciona: o cliente guarda a mensagem num **outbox local** e reenvia ao reconectar.

### RF6 — Histórico local
1. O SQLite guarda contatos, mensagens (texto claro) e o outbox.
2. O arquivo tem permissão `600`. A cifragem do banco local fica para v1.1 (ver §9).
3. `/clear <contato>` apaga o histórico local da conversa.

## 4. Requisitos não funcionais

| Categoria | Requisito |
|-----------|-----------|
| Segurança | TLS obrigatório fora do dev (`https`/`wss`); o servidor nunca loga corpo de envelope; segredos via variáveis de ambiente |
| Privacidade | O servidor não guarda histórico; envelopes entregues são apagados na hora; TTL de 30 dias para os não entregues |
| Desempenho | Latência de relay p95 < 100 ms com ambos online (em uma instância) |
| Escala v1 | Instância única (sessões WS em memória). Para escalar horizontalmente: Redis pub/sub (§9) |
| Portabilidade | Cliente distribuído como fat-jar (`java -jar terminal-send.jar`), roda em macOS, Linux e Windows Terminal |
| Observabilidade | Spring Actuator (`/actuator/health`, métricas), logs JSON estruturados |
| Testes | Unitários + integração com Testcontainers (Postgres) e GreenMail (SMTP) |

## 5. API REST (`/api/v1`)

Todas as rotas, exceto `/auth/*`, exigem `Authorization: Bearer <accessToken>`.
Os erros seguem o formato **RFC 9457** (`application/problem+json`), com `code` estável.

| Método | Rota | Corpo | Resposta |
|--------|------|-------|----------|
| POST | `/auth/register` | `{email, password}` | `201` `{userId, handle}` |
| POST | `/auth/verify` | `{email, password, code}` | `200` `TokenPair` |
| POST | `/auth/verify/resend` | `{email}` | `202` |
| POST | `/auth/login` | `{email, password}` | `200` `TokenPair` · `403 EMAIL_NOT_VERIFIED` |
| POST | `/auth/refresh` | `{refreshToken}` | `200` `TokenPair` |
| POST | `/auth/logout` | `{refreshToken}` | `204` |
| GET | `/me` | — | `UserView` |
| PUT | `/me/key` | `{publicKey}` (base64, 32 bytes) | `204` |
| GET | `/users/{userId}/key` | — | `{publicKey, fingerprint, updatedAt}` (só se `ACCEPTED`) |
| POST | `/connections` | `{target}` (email ou handle) | `202` |
| GET | `/connections?status=PENDING\|ACCEPTED` | — | `ConnectionView[]` |
| POST | `/connections/{id}/accept` | — | `200 ConnectionView` |
| POST | `/connections/{id}/reject` | — | `204` |
| DELETE | `/connections/{id}` | — | `204` |

`TokenPair = {accessToken, accessTokenExpiresAt, refreshToken, user: UserView}`
`UserView = {id, email, handle, emailVerified}`
`ConnectionView = {id, peer: {id, email, handle}, status, direction: INCOMING|OUTGOING, createdAt}`

## 6. Protocolo WebSocket (`/ws`)

- Handshake com header `Authorization: Bearer <accessToken>`; token inválido → `401` no upgrade.
- Frames de texto em JSON, sempre com `type`. Ping/pong nativo a cada 30 s.
- Uma sessão por usuário: uma nova conexão fecha a anterior com o código `4001 SESSION_REPLACED`.
- Quando o token expira, o servidor fecha com `4401` e o cliente faz refresh e reconecta.

### Cliente → servidor

```jsonc
{ "type": "message.send", "id": "0192…uuidv7", "to": "<userId>",
  "recipientKeyFp": "a1b2…", "nonce": "<b64 12B>", "ciphertext": "<b64>", "sentAt": "2026-10-05T12:00:00Z" }
{ "type": "message.ack", "ids": ["0192…", "0193…"] }
```

### Servidor → cliente

```jsonc
{ "type": "message.accepted",  "id": "…" }                                   // persistido/entregue
{ "type": "message.deliver",   "id": "…", "from": "<userId>", "senderKeyFp": "…",
  "nonce": "…", "ciphertext": "…", "sentAt": "…" }
{ "type": "message.delivered", "id": "…" }                                   // destinatário confirmou
{ "type": "message.rejected",  "id": "…", "code": "NOT_CONNECTED|KEY_MISMATCH|TOO_LARGE|RATE_LIMITED" }
{ "type": "connection.requested", "connection": { /* ConnectionView */ } }
{ "type": "connection.accepted",  "connection": { /* ConnectionView */ } }
{ "type": "connection.removed",   "connectionId": "…" }
{ "type": "key.changed", "userId": "…", "fingerprint": "…" }
{ "type": "error", "code": "…", "message": "…" }
```

Ao conectar, o servidor envia todos os `message.deliver` pendentes, em ordem de `created_at`.

`KEY_MISMATCH`: o `recipientKeyFp` não confere com a chave atual do destinatário. O cliente
busca a chave nova, avisa o usuário (TOFU) e só recifra depois da confirmação.

## 7. Criptografia

### 7.1 Primitivas (todas no JDK 21)
- Acordo de chaves: `KeyPairGenerator("X25519")` / `KeyAgreement("X25519")`.
- KDF: HKDF-SHA256 implementado sobre `Mac("HmacSHA256")`, porque `javax.crypto.KDF` só existe a partir do JDK 24.
- Cifra: `Cipher("AES/GCM/NoPadding")`, chave de 256 bits, nonce aleatório de 96 bits e tag de 128 bits.

### 7.2 Chave de sessão por par
```
shared   = X25519(myPriv, peerPub)
salt     = SHA-256( min(idA,idB) || max(idA,idB) )
key      = HKDF(shared, salt, info = "terminal-send/v1/msg", L = 32)
```
A chave fica em cache em memória por contato e é invalidada quando a chave do contato muda.

### 7.3 Envelope
```
plaintext = JSON { "body": "...", "sentAt": "..." }
aad       = "v1|" + messageId + "|" + senderId + "|" + recipientId
ct        = AES-GCM(key, nonce, plaintext, aad)
```
O AAD impede que o servidor troque o remetente ou o destinatário, ou faça replay do envelope para outra conversa.

### 7.4 Limitações conhecidas (aceitas para v1)
- **Sem forward secrecy**: comprometer a chave privada estática expõe todas as mensagens daquele par de chaves. Mitigação futura: X3DH + Double Ratchet.
- **Confiança no diretório de chaves**: o servidor poderia servir uma chave falsa. Mitigação: TOFU + comparação manual de fingerprint (`/verify <contato>`).
- Metadados (quem fala com quem, quando e o tamanho das mensagens) ficam visíveis para o servidor.

## 8. Modelo de dados

### 8.1 Servidor (PostgreSQL)

```sql
users(id uuid pk, email citext unique, handle varchar(9) unique, password_hash text,
      email_verified_at timestamptz, public_key bytea, public_key_fp varchar(64),
      key_updated_at timestamptz, created_at timestamptz)

email_verifications(id uuid pk, user_id fk, code_hash text, expires_at, attempts int,
                    consumed_at, created_at)

refresh_tokens(id uuid pk, user_id fk, token_hash text unique, family_id uuid,
               expires_at, revoked_at, created_at)

connections(id uuid pk, requester_id fk, addressee_id fk,
            status varchar  -- PENDING | ACCEPTED | REJECTED
            , created_at, responded_at,
            unique (least(requester_id, addressee_id), greatest(requester_id, addressee_id)))

pending_messages(id uuid pk /* do cliente */, sender_id fk, recipient_id fk,
                 recipient_key_fp varchar(64), nonce bytea, ciphertext bytea,
                 sent_at timestamptz, created_at timestamptz, expires_at timestamptz)
  index (recipient_id, created_at)
```

### 8.2 Cliente (SQLite)

```sql
contacts(user_id text pk, email text, handle text, public_key blob, fingerprint text,
         fingerprint_verified int, status text, updated_at text)
messages(id text pk, contact_id text, direction text /* IN|OUT */, body text,
         sent_at text, received_at text,
         state text /* QUEUED|SENT|DELIVERED|FAILED|RECEIVED */)
  index (contact_id, sent_at)
outbox(message_id text pk, envelope_json text, attempts int, next_attempt_at text)
kv(key text pk, value text)   -- cursor, preferências
```

Os arquivos do cliente ficam em `~/.terminal-send/`:

```
config.properties          # serverUrl, tema
<handle>/identity.key      # chave privada cifrada com a senha (600)
<handle>/session.json      # refresh token (600)
<handle>/history.db        # SQLite (600)
```

## 9. Evolução futura

1. Recuperação de senha via código por email.
2. Cifrar o SQLite local (SQLCipher ou cifragem por coluna com uma chave derivada da senha).
3. Escala horizontal: roteamento entre instâncias via Redis pub/sub, com sessões WS sticky.
4. X3DH + Double Ratchet para forward secrecy.
5. Multi-dispositivo (uma chave por device, fan-out no envio).

## 10. Plano de entrega (milestones)

| Marco | Entrega | Critério de pronto |
|-------|---------|--------------------|
| M0 | Tech-spec + arquitetura | Documentos revisados |
| M1 | Esqueleto Gradle multi-módulo, docker-compose, Flyway V1, CI | `./gradlew build` verde no CI |
| M2 | Auth: register/verify/login/refresh + email (Mailpit) | Testes de integração com GreenMail |
| M3 | Conexões (convite/aceite/remoção) | Testes de API + regras de anti-enumeração |
| M4 | Biblioteca de cripto do cliente + `PUT /me/key` | Testes de ida e volta, AAD adulterado falha |
| M5 | Relay WebSocket + store-and-forward + ACK | Teste e2e com 2 clientes headless |
| M6 | Cliente TUI (Lanterna) + SQLite + outbox | Demo manual: 2 terminais conversando |
| M7 | Hardening: rate limit, TLS, empacotamento, jobs de limpeza | Checklist de segurança |

## 11. Riscos

| Risco | Impacto | Mitigação |
|-------|---------|-----------|
| Lanterna com bugs em alguns terminais (Windows) | UX | A camada de UI fica atrás de uma interface, com fallback para um REPL simples |
| Emails caindo em spam em prod | Onboarding | SMTP com SPF/DKIM (Resend/SES) e reenvio de código |
| Usuário perde a máquina e, com ela, o histórico e a chave | Dados | Comportamento esperado e documentado ("salvo só localmente") |
| Instância única é ponto único de falha | Disponibilidade | Aceitável na v1; Redis pub/sub no roadmap |
