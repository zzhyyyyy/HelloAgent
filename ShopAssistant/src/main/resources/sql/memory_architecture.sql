CREATE TABLE IF NOT EXISTS memory_event
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          VARCHAR(128) NOT NULL,
    owner_id           VARCHAR(256) NOT NULL,
    session_id         UUID         NOT NULL,
    agent_id           UUID         NULL,
    agent_role         VARCHAR(256) NULL,
    event_type         VARCHAR(64)  NOT NULL,
    event_key          VARCHAR(256) NOT NULL DEFAULT 'message',
    role               VARCHAR(32)  NULL,
    content            TEXT         NULL,
    structured_payload JSONB        NOT NULL DEFAULT '{}'::jsonb,
    source_type        VARCHAR(64)  NOT NULL,
    trust_level        VARCHAR(64)  NOT NULL,
    source_message_id  UUID         NULL,
    sequence_no        BIGSERIAL    NOT NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_memory_event_source
    ON memory_event (source_message_id, event_type, event_key)
    WHERE source_message_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_memory_event_owner_sequence
    ON memory_event (tenant_id, owner_id, sequence_no DESC);

CREATE INDEX IF NOT EXISTS idx_memory_event_session_sequence
    ON memory_event (tenant_id, owner_id, session_id, sequence_no);

CREATE INDEX IF NOT EXISTS idx_memory_event_type_created
    ON memory_event (event_type, created_at DESC);

CREATE TABLE IF NOT EXISTS memory_note
(
    id                 UUID PRIMARY KEY,
    tenant_id          VARCHAR(128) NOT NULL,
    owner_id           VARCHAR(256) NOT NULL,
    agent_id           UUID         NULL,
    scope              VARCHAR(32)  NOT NULL,
    memory_type        VARCHAR(32)  NOT NULL,
    subject            VARCHAR(256) NOT NULL,
    content            TEXT         NOT NULL,
    structured_content JSONB        NOT NULL DEFAULT '{}'::jsonb,
    confidence         DOUBLE PRECISION NOT NULL,
    salience           DOUBLE PRECISION NOT NULL,
    status             VARCHAR(32)  NOT NULL,
    version            INTEGER      NOT NULL,
    previous_note_id   UUID         NULL,
    source             VARCHAR(64)  NOT NULL,
    evidence_count     INTEGER      NOT NULL DEFAULT 1,
    valid_from         TIMESTAMP    NOT NULL,
    valid_to           TIMESTAMP    NULL,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_memory_note_active_subject
    ON memory_note (tenant_id, owner_id, agent_id, scope, subject)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_memory_note_owner_rank
    ON memory_note (tenant_id, owner_id, status, salience DESC, confidence DESC);

CREATE TABLE IF NOT EXISTS memory_note_evidence
(
    id               UUID PRIMARY KEY,
    note_id          UUID         NOT NULL REFERENCES memory_note(id) ON DELETE CASCADE,
    event_id         UUID         NOT NULL REFERENCES memory_event(id) ON DELETE CASCADE,
    evidence_role    VARCHAR(32)  NOT NULL,
    evidence_excerpt VARCHAR(512) NULL,
    created_at       TIMESTAMP    NOT NULL,
    UNIQUE (note_id, event_id, evidence_role)
);

CREATE TABLE IF NOT EXISTS memory_representation
(
    id                  UUID PRIMARY KEY,
    tenant_id           VARCHAR(128) NOT NULL,
    owner_id            VARCHAR(256) NOT NULL,
    agent_id            UUID         NULL,
    representation_type VARCHAR(32)  NOT NULL,
    slug                VARCHAR(256) NOT NULL,
    title               VARCHAR(512) NOT NULL,
    summary             TEXT         NULL,
    body                TEXT         NOT NULL,
    links               JSONB        NOT NULL DEFAULT '[]'::jsonb,
    status              VARCHAR(32)  NOT NULL,
    version             INTEGER      NOT NULL,
    snapshot_id         UUID         NULL,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_memory_representation_active_slug
    ON memory_representation (tenant_id, owner_id, agent_id, slug)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_memory_representation_search
    ON memory_representation USING GIN
        (to_tsvector('simple', COALESCE(title, '') || ' ' || COALESCE(summary, '') || ' ' || COALESCE(body, '')));

CREATE INDEX IF NOT EXISTS idx_memory_note_search
    ON memory_note USING GIN
        (to_tsvector('simple', COALESCE(subject, '') || ' ' || COALESCE(content, '')));

CREATE TABLE IF NOT EXISTS memory_snapshot
(
    id                   UUID PRIMARY KEY,
    tenant_id            VARCHAR(128) NOT NULL,
    owner_id             VARCHAR(256) NOT NULL,
    agent_id             UUID         NULL,
    version              INTEGER      NOT NULL,
    status               VARCHAR(32)  NOT NULL,
    previous_snapshot_id UUID         NULL,
    summary              TEXT         NULL,
    created_at           TIMESTAMP    NOT NULL,
    activated_at         TIMESTAMP    NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_memory_snapshot_active
    ON memory_snapshot (tenant_id, owner_id, agent_id)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX IF NOT EXISTS uk_memory_snapshot_version
    ON memory_snapshot (tenant_id, owner_id, agent_id, version);

CREATE TABLE IF NOT EXISTS memory_snapshot_item
(
    snapshot_id UUID        NOT NULL REFERENCES memory_snapshot(id) ON DELETE CASCADE,
    item_type   VARCHAR(32) NOT NULL,
    item_id     UUID        NOT NULL,
    PRIMARY KEY (snapshot_id, item_type, item_id)
);

CREATE TABLE IF NOT EXISTS memory_dream_job
(
    id                 UUID PRIMARY KEY,
    tenant_id          VARCHAR(128) NOT NULL,
    owner_id           VARCHAR(256) NOT NULL,
    agent_id           UUID         NULL,
    input_snapshot_id  UUID         NULL,
    output_snapshot_id UUID         NULL,
    instructions       TEXT         NULL,
    status             VARCHAR(32)  NOT NULL,
    summary            TEXT         NULL,
    error_message      TEXT         NULL,
    created_at         TIMESTAMP    NOT NULL,
    started_at         TIMESTAMP    NULL,
    completed_at       TIMESTAMP    NULL
);

CREATE TABLE IF NOT EXISTS memory_change_proposal
(
    id            UUID PRIMARY KEY,
    dream_job_id  UUID         NOT NULL REFERENCES memory_dream_job(id) ON DELETE CASCADE,
    snapshot_id   UUID         NOT NULL REFERENCES memory_snapshot(id) ON DELETE CASCADE,
    change_type   VARCHAR(32)  NOT NULL,
    target_type   VARCHAR(32)  NOT NULL,
    target_id     UUID         NOT NULL,
    rationale     TEXT         NULL,
    evidence      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    status        VARCHAR(32)  NOT NULL,
    created_at    TIMESTAMP    NOT NULL,
    decided_at    TIMESTAMP    NULL
);

CREATE INDEX IF NOT EXISTS idx_memory_dream_job_scope_status
    ON memory_dream_job (tenant_id, owner_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_memory_change_proposal_snapshot
    ON memory_change_proposal (snapshot_id, status);
