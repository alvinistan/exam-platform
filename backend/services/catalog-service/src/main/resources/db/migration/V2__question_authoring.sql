-- Phase 7: question authoring support.

-- Validated import files waiting for the admin to confirm (FR-QB-08). Nothing reaches a bank until confirmed.
CREATE TABLE catalog.question_import (
    id                    uuid PRIMARY KEY,
    exam_id               uuid        NOT NULL REFERENCES catalog.exam (id),
    bank                  text        NOT NULL CHECK (bank IN ('TRIAL', 'MAIN')),
    file_name             text        NOT NULL,
    total_rows            integer     NOT NULL CHECK (total_rows >= 0),
    valid_rows            jsonb       NOT NULL DEFAULT '[]'::jsonb,   -- parsed, sanitised question content
    errors                jsonb       NOT NULL DEFAULT '[]'::jsonb,
    status                text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CONFIRMED')),
    created_question_ids  uuid[]      NOT NULL DEFAULT '{}',
    created_by            uuid        NOT NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    expires_at            timestamptz NOT NULL,
    confirmed_at          timestamptz,
    CHECK (status <> 'CONFIRMED' OR confirmed_at IS NOT NULL)
);
CREATE INDEX question_import_expiry_idx ON catalog.question_import (expires_at) WHERE status = 'PENDING';

-- Search by tag and the admin list's default sort (FR-QB-07).
CREATE INDEX question_tag_tag_idx ON bank_trial.question_tag (tag);
CREATE INDEX question_tag_tag_idx ON bank_main.question_tag (tag);
CREATE INDEX question_updated_idx ON bank_trial.question (updated_at DESC);
CREATE INDEX question_updated_idx ON bank_main.question (updated_at DESC);

-- Who uploaded an image, for clean-up of unused uploads later.
CREATE INDEX media_object_uploaded_idx ON catalog.media_object (uploaded_at);
