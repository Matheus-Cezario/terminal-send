CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id                UUID PRIMARY KEY,
    email             CITEXT       NOT NULL UNIQUE,
    handle            VARCHAR(9)   NOT NULL UNIQUE,
    password_hash     TEXT         NOT NULL,
    email_verified_at TIMESTAMPTZ,
    public_key        BYTEA,
    public_key_fp     VARCHAR(64),
    key_updated_at    TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL
);

CREATE TABLE email_verifications (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash   TEXT        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX email_verifications_user_idx ON email_verifications (user_id, created_at DESC);

CREATE TABLE refresh_tokens (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash TEXT        NOT NULL UNIQUE,
    family_id  UUID        NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);

CREATE TABLE connections (
    id           UUID PRIMARY KEY,
    requester_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    addressee_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status       VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')),
    created_at   TIMESTAMPTZ NOT NULL,
    responded_at TIMESTAMPTZ,
    CHECK (requester_id <> addressee_id)
);
-- One connection per unordered pair of users.
CREATE UNIQUE INDEX connections_pair_uq
    ON connections (LEAST(requester_id, addressee_id), GREATEST(requester_id, addressee_id));
CREATE INDEX connections_addressee_idx ON connections (addressee_id, status);

CREATE TABLE pending_messages (
    id               UUID PRIMARY KEY,
    sender_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    recipient_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    recipient_key_fp VARCHAR(64) NOT NULL,
    nonce            BYTEA       NOT NULL,
    ciphertext       BYTEA       NOT NULL,
    sent_at          TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX pending_messages_recipient_idx ON pending_messages (recipient_id, created_at);
CREATE INDEX pending_messages_expiry_idx ON pending_messages (expires_at);
