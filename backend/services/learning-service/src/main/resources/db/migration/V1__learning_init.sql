-- learning-service · database: learning · owner: learning-service only
-- Append-only answer history, topic mastery, learning levels, misconceptions, recommendations, goals.

CREATE FUNCTION forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$;

-- Idempotent event consumption (FR-LRN-01) and feed cursors (ADR-006).
CREATE TABLE processed_event (
    event_id      uuid PRIMARY KEY,
    source        text        NOT NULL,
    processed_at  timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE feed_cursor (
    source     text PRIMARY KEY,
    last_seq   bigint      NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE answer_history (
    id                   bigserial PRIMARY KEY,
    student_id           uuid        NOT NULL,
    attempt_id           uuid        NOT NULL,
    exam_id              uuid        NOT NULL,
    mode                 text        NOT NULL,
    topic_id             uuid        NOT NULL,
    question_id          uuid        NOT NULL,
    question_version_id  uuid        NOT NULL,
    difficulty           text        NOT NULL CHECK (difficulty IN ('SIMPLE', 'MEDIUM', 'HARD')),
    outcome              text        NOT NULL CHECK (outcome IN ('CORRECT', 'INCORRECT', 'UNANSWERED')),
    selected_labels      char(1)[]   NOT NULL DEFAULT '{}',
    misconception_id     uuid,
    time_spent_ms        integer,
    answered_at          timestamptz NOT NULL,
    UNIQUE (attempt_id, question_id)
);
CREATE INDEX answer_history_topic_idx    ON answer_history (student_id, topic_id, answered_at DESC);
CREATE INDEX answer_history_question_idx ON answer_history (student_id, question_id, answered_at DESC);
CREATE TRIGGER answer_history_append_only BEFORE UPDATE OR DELETE ON answer_history
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

CREATE TABLE topic_mastery (
    student_id                uuid         NOT NULL,
    topic_id                  uuid         NOT NULL,
    exam_id                   uuid         NOT NULL,
    attempted                 integer      NOT NULL DEFAULT 0,
    correct                   integer      NOT NULL DEFAULT 0,
    window_size               integer      NOT NULL DEFAULT 0,
    weighted_accuracy         numeric(5,4) CHECK (weighted_accuracy BETWEEN 0 AND 1),
    state                     text         NOT NULL DEFAULT 'INSUFFICIENT'
                              CHECK (state IN ('STRONG', 'DEVELOPING', 'REVIEW', 'INSUFFICIENT')),
    level                     smallint     NOT NULL DEFAULT 1 CHECK (level BETWEEN 1 AND 3),
    answers_since_level_change integer     NOT NULL DEFAULT 0,
    last_practised_at         timestamptz,
    updated_at                timestamptz  NOT NULL DEFAULT now(),
    version                   integer      NOT NULL DEFAULT 0,
    PRIMARY KEY (student_id, topic_id),
    CHECK (correct <= attempted)
);
CREATE INDEX topic_mastery_exam_idx ON topic_mastery (student_id, exam_id, state);

-- Every level change keeps its reason and evidence (FR-LRN-05, US-37).
CREATE TABLE level_change (
    id          bigserial PRIMARY KEY,
    student_id  uuid        NOT NULL,
    topic_id    uuid        NOT NULL,
    from_level  smallint    NOT NULL,
    to_level    smallint    NOT NULL,
    rule_id     text        NOT NULL,                        -- e.g. AD-4, AD-5
    reason      text        NOT NULL,
    evidence    jsonb       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CHECK (from_level <> to_level)
);
CREATE TRIGGER level_change_append_only BEFORE UPDATE OR DELETE ON level_change
    FOR EACH ROW EXECUTE FUNCTION forbid_change();

CREATE TABLE misconception_observation (
    id                bigserial PRIMARY KEY,
    student_id        uuid        NOT NULL,
    misconception_id  uuid        NOT NULL,
    topic_id          uuid        NOT NULL,
    question_id       uuid        NOT NULL,
    attempt_id        uuid        NOT NULL,
    observed_at       timestamptz NOT NULL,
    UNIQUE (attempt_id, question_id)
);
CREATE INDEX misconception_obs_idx ON misconception_observation (student_id, misconception_id, observed_at DESC);

CREATE TABLE learning_goal (                                -- FR-LRN-12
    id            uuid PRIMARY KEY,
    student_id    uuid        NOT NULL,
    exam_id       uuid        NOT NULL,
    topic_ids     uuid[]      NOT NULL CHECK (cardinality(topic_ids) >= 1),
    target_state  text        NOT NULL CHECK (target_state IN ('DEVELOPING', 'STRONG')),
    status        text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ACHIEVED', 'CANCELLED')),
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE recommendation (
    id                 uuid PRIMARY KEY,
    student_id         uuid        NOT NULL,
    exam_id            uuid        NOT NULL,
    source_attempt_id  uuid,
    type               text        NOT NULL CHECK (type IN ('TARGETED', 'RETRY_MISSED', 'STEP_UP', 'REVISIT', 'BALANCED', 'GOAL')),
    topic_ids          uuid[]      NOT NULL,
    question_count     integer     NOT NULL CHECK (question_count BETWEEN 1 AND 500),
    level              smallint    CHECK (level BETWEEN 1 AND 3),
    reason             text        NOT NULL,                 -- plain-language, built from real numbers (FR-LRN-07)
    evidence           jsonb       NOT NULL,
    status             text        NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING', 'STARTED', 'DISMISSED', 'SUPERSEDED')),
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX recommendation_one_pending_uq ON recommendation (student_id, exam_id) WHERE status = 'PENDING';

-- Mirror of published exam topics for adaptive planning, fed by catalog events.
CREATE TABLE topic_view (
    topic_id     uuid PRIMARY KEY,
    exam_ids     uuid[]      NOT NULL,
    prerequisite_topic_ids uuid[] NOT NULL DEFAULT '{}',
    updated_at   timestamptz NOT NULL DEFAULT now()
);
