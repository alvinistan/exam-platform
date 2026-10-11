-- assessment-service · database: assessment · owner: assessment-service only
-- Attempts, their fixed question sets (with an answer-key snapshot), responses and results.

CREATE TABLE attempt (
    id                 uuid PRIMARY KEY,
    student_id         uuid        NOT NULL,                 -- identity id; no cross-database FK
    exam_id            uuid        NOT NULL,                 -- catalog id
    mode               text        NOT NULL CHECK (mode IN ('PRACTICE', 'MOCK', 'USER_FRIENDLY')),
    generation         text        NOT NULL DEFAULT 'COUNT' CHECK (generation IN ('COUNT', 'TIME', 'FIXED', 'RECOMMENDATION')),
    requested_count    integer,
    requested_seconds  integer,
    bank               text        NOT NULL CHECK (bank IN ('TRIAL', 'MAIN')),
    access_reason      text        NOT NULL,                 -- reason code returned by the access decision (audit trail)
    config_snapshot    jsonb       NOT NULL,                 -- mode config as it was at start
    status             text        NOT NULL DEFAULT 'IN_PROGRESS'
                       CHECK (status IN ('IN_PROGRESS', 'SCORED')),
    finish_reason      text        CHECK (finish_reason IN ('SUBMITTED', 'EXPIRED', 'ABANDONED')),
    started_at         timestamptz NOT NULL DEFAULT now(),
    expires_at         timestamptz,                          -- timed attempts only (TM-1)
    last_activity_at   timestamptz NOT NULL DEFAULT now(),
    finished_at        timestamptz,
    score              numeric(8,2),
    max_score          numeric(8,2),
    correct_count      integer,
    incorrect_count    integer,
    unanswered_count   integer,
    idempotency_key    text        NOT NULL,                 -- client-generated key for "start attempt"
    version            integer     NOT NULL DEFAULT 0,
    UNIQUE (student_id, idempotency_key),
    CHECK (mode <> 'MOCK' OR expires_at IS NOT NULL),
    CHECK (expires_at IS NULL OR expires_at > started_at),
    CHECK ((status = 'SCORED') = (finish_reason IS NOT NULL AND finished_at IS NOT NULL AND score IS NOT NULL))
);
-- TM-4: at most one active mock attempt per student per exam.
CREATE UNIQUE INDEX attempt_one_active_mock_uq ON attempt (student_id, exam_id)
    WHERE mode = 'MOCK' AND status = 'IN_PROGRESS';
CREATE INDEX attempt_student_idx ON attempt (student_id, started_at DESC);
CREATE INDEX attempt_open_timed_idx ON attempt (expires_at) WHERE status = 'IN_PROGRESS' AND expires_at IS NOT NULL;
CREATE INDEX attempt_open_idle_idx ON attempt (last_activity_at) WHERE status = 'IN_PROGRESS' AND expires_at IS NULL;

-- The question set is fixed when the attempt is created (FR-ATT-02).
-- The answer key is copied here so scoring never depends on another service being up.
CREATE TABLE attempt_question (
    attempt_id           uuid     NOT NULL REFERENCES attempt (id) ON DELETE CASCADE,
    position             integer  NOT NULL CHECK (position >= 1),
    question_id          uuid     NOT NULL,
    question_version_id  uuid     NOT NULL,
    topic_id             uuid     NOT NULL,
    difficulty           text     NOT NULL CHECK (difficulty IN ('SIMPLE', 'MEDIUM', 'HARD')),
    est_seconds          integer  NOT NULL,
    answer_type          text     NOT NULL CHECK (answer_type IN ('SINGLE', 'MULTI')),
    option_order         char(1)[] NOT NULL,                 -- shuffled display order of labels
    correct_labels       char(1)[] NOT NULL,                 -- never sent to the client before disclosure
    misconception_by_label jsonb  NOT NULL DEFAULT '{}'::jsonb,
    marks                numeric(5,2) NOT NULL,
    PRIMARY KEY (attempt_id, position),
    UNIQUE (attempt_id, question_id),
    CHECK (cardinality(correct_labels) >= 1),
    CHECK (answer_type = 'MULTI' OR cardinality(correct_labels) = 1)
);

CREATE TABLE response (
    attempt_id      uuid     NOT NULL,
    position        integer  NOT NULL,
    selected_labels char(1)[] NOT NULL DEFAULT '{}',
    flagged         boolean  NOT NULL DEFAULT false,
    locked          boolean  NOT NULL DEFAULT false,         -- practice: answer locked after "check"
    time_spent_ms   integer  NOT NULL DEFAULT 0 CHECK (time_spent_ms >= 0),
    answered_at     timestamptz,
    is_correct      boolean,                                 -- filled at scoring (or at check in practice)
    awarded         numeric(5,2),
    PRIMARY KEY (attempt_id, position),
    FOREIGN KEY (attempt_id, position) REFERENCES attempt_question (attempt_id, position) ON DELETE CASCADE
);

CREATE TABLE outbox_event (
    seq            bigserial PRIMARY KEY,
    id             uuid        NOT NULL UNIQUE,
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    event_type     text        NOT NULL,                     -- AttemptStarted, AttemptCompleted
    payload        jsonb       NOT NULL,
    occurred_at    timestamptz NOT NULL DEFAULT now()
);
