-- Ara server-side schema (spec §5).
-- PRIVACY INVARIANT: there is no table for message content anywhere in this
-- schema. The server relays opaque encrypted envelopes and pushes wake-ups —
-- durable message storage lives exclusively on user devices.

CREATE TABLE IF NOT EXISTS users (
    id                 TEXT PRIMARY KEY,            -- Ara ID, e.g. ARA-XXXX-XXXX-XXXX-XXXX
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    recovery_email     TEXT,
    phone_hash         TEXT,                        -- SHA-256; nullable (phone path is optional)
    public_key_bundle  JSONB NOT NULL,              -- identity + signed prekey (public halves only)
    last_seen          TIMESTAMPTZ,
    status_visibility  BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS sessions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id           TEXT NOT NULL,
    refresh_token_hash  TEXT NOT NULL,               -- SHA-256 of the refresh token
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);

CREATE TABLE IF NOT EXISTS groups (
    id           TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    photo_url    TEXT,
    description  TEXT,
    owner_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    member_limit INT NOT NULL DEFAULT 256
);

CREATE TABLE IF NOT EXISTS group_members (
    group_id  TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role      TEXT NOT NULL DEFAULT 'MEMBER',        -- OWNER | ADMIN | MEMBER
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    muted     BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (group_id, user_id)
);

CREATE TABLE IF NOT EXISTS devices (
    id          TEXT NOT NULL,                       -- client-generated device id
    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    push_token  TEXT,
    platform    TEXT NOT NULL DEFAULT 'android',
    public_key  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, id)
);

CREATE TABLE IF NOT EXISTS blocked_users (
    blocker_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id)
);

-- X3DH one-time prekeys (public halves only). The server pops one per bundle
-- fetch and never sees the private halves.
CREATE TABLE IF NOT EXISTS one_time_prekeys (
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    key_id     INT  NOT NULL,
    public_key TEXT NOT NULL,                        -- base64 X25519 public key
    used_at    TIMESTAMPTZ,
    PRIMARY KEY (user_id, key_id)
);
CREATE INDEX IF NOT EXISTS idx_opk_unused ON one_time_prekeys(user_id) WHERE used_at IS NULL;
