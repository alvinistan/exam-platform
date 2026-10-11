-- catalog-service · database: catalog · owner: catalog-service only
-- Schema "catalog": categories, exams, content tree, mode configuration, misconceptions.
-- Schemas "bank_trial" and "bank_main": the two question banks, identical structure, separate access (FR-QB-10).

CREATE SCHEMA IF NOT EXISTS catalog;

CREATE FUNCTION catalog.forbid_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END $$;

CREATE TABLE catalog.exam_category (
    id             uuid PRIMARY KEY,
    name           text        NOT NULL,
    slug           text        NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    description    text,
    icon_key       text,
    display_order  integer     NOT NULL DEFAULT 0,
    status         text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        integer     NOT NULL DEFAULT 0
);

CREATE TABLE catalog.exam (
    id                 uuid PRIMARY KEY,
    category_id        uuid        NOT NULL REFERENCES catalog.exam_category (id),
    title              text        NOT NULL CHECK (length(title) BETWEEN 2 AND 160),
    slug               text        NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' AND length(slug) <= 80),
    short_description  text        NOT NULL DEFAULT '',
    instructions       text        NOT NULL DEFAULT '',
    cover_media_key    text,
    seo_title          text,
    seo_description    text,
    display_order      integer,                       -- catalogue order used by free-access rule FA-1
    featured           boolean     NOT NULL DEFAULT false,
    status             text        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    published_at       timestamptz,
    created_by         uuid        NOT NULL,
    updated_by         uuid        NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            integer     NOT NULL DEFAULT 0,
    CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);
CREATE UNIQUE INDEX exam_display_order_uq ON catalog.exam (display_order) WHERE status = 'PUBLISHED';
CREATE INDEX exam_category_idx ON catalog.exam (category_id, status);

-- Retired slugs keep redirecting (301) and can never be reused (FR-CAT-06).
CREATE TABLE catalog.exam_slug_history (
    slug        text PRIMARY KEY,
    exam_id     uuid        NOT NULL REFERENCES catalog.exam (id),
    retired_at  timestamptz NOT NULL DEFAULT now()
);

CREATE FUNCTION catalog.slug_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME = 'exam' THEN
        IF EXISTS (SELECT 1 FROM catalog.exam_slug_history h WHERE h.slug = NEW.slug AND h.exam_id <> NEW.id) THEN
            RAISE EXCEPTION 'slug "%" was used by another exam and cannot be reused', NEW.slug USING ERRCODE = 'unique_violation';
        END IF;
        IF TG_OP = 'UPDATE' AND NEW.slug <> OLD.slug THEN
            DELETE FROM catalog.exam_slug_history WHERE slug = NEW.slug AND exam_id = NEW.id;   -- moving back to an own old slug
            INSERT INTO catalog.exam_slug_history (slug, exam_id) VALUES (OLD.slug, OLD.id)
                ON CONFLICT (slug) DO NOTHING;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER exam_slug_guard BEFORE INSERT OR UPDATE OF slug ON catalog.exam
    FOR EACH ROW EXECUTE FUNCTION catalog.slug_guard();

-- Content tree: subjects and modules are reusable across exams by linking (FR-CAT-03).
CREATE TABLE catalog.subject (
    id          uuid PRIMARY KEY,
    title       text        NOT NULL,
    code        text        UNIQUE,
    description text,
    status      text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    version     integer     NOT NULL DEFAULT 0
);

CREATE TABLE catalog.module (
    id             uuid PRIMARY KEY,
    subject_id     uuid        NOT NULL REFERENCES catalog.subject (id),
    title          text        NOT NULL,
    display_order  integer     NOT NULL DEFAULT 0,
    status         text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version        integer     NOT NULL DEFAULT 0
);
CREATE INDEX module_subject_idx ON catalog.module (subject_id);

CREATE TABLE catalog.topic (
    id             uuid PRIMARY KEY,
    module_id      uuid        NOT NULL REFERENCES catalog.module (id),
    title          text        NOT NULL,
    display_order  integer     NOT NULL DEFAULT 0,
    status         text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version        integer     NOT NULL DEFAULT 0
);
CREATE INDEX topic_module_idx ON catalog.topic (module_id);

CREATE TABLE catalog.exam_subject (
    exam_id        uuid    NOT NULL REFERENCES catalog.exam (id) ON DELETE CASCADE,
    subject_id     uuid    NOT NULL REFERENCES catalog.subject (id),
    display_order  integer NOT NULL DEFAULT 0,
    PRIMARY KEY (exam_id, subject_id)
);

CREATE TABLE catalog.topic_prerequisite (                    -- FR-CAT-13
    topic_id               uuid NOT NULL REFERENCES catalog.topic (id) ON DELETE CASCADE,
    prerequisite_topic_id  uuid NOT NULL REFERENCES catalog.topic (id) ON DELETE CASCADE,
    PRIMARY KEY (topic_id, prerequisite_topic_id),
    CHECK (topic_id <> prerequisite_topic_id)
);

-- Per-exam, per-mode configuration (FR-CAT-07). Only settings with a server implementation exist here.
CREATE TABLE catalog.exam_mode_config (
    exam_id               uuid     NOT NULL REFERENCES catalog.exam (id) ON DELETE CASCADE,
    mode                  text     NOT NULL CHECK (mode IN ('PRACTICE', 'MOCK', 'USER_FRIENDLY')),
    enabled               boolean  NOT NULL DEFAULT false,
    default_count         integer  NOT NULL DEFAULT 10,
    min_count             integer  NOT NULL DEFAULT 5,
    max_count             integer  NOT NULL DEFAULT 50,
    time_limit_seconds    integer  CHECK (time_limit_seconds IS NULL OR time_limit_seconds BETWEEN 60 AND 43200),
    per_question_seconds  integer  CHECK (per_question_seconds IS NULL OR per_question_seconds BETWEEN 10 AND 3600),
    mix_simple_pct        smallint NOT NULL DEFAULT 30,
    mix_medium_pct        smallint NOT NULL DEFAULT 50,
    mix_hard_pct          smallint NOT NULL DEFAULT 20,
    shuffle_questions     boolean  NOT NULL DEFAULT true,
    shuffle_options       boolean  NOT NULL DEFAULT true,
    feedback_timing       text     NOT NULL DEFAULT 'AFTER_SUBMIT' CHECK (feedback_timing IN ('IMMEDIATE', 'AFTER_SUBMIT')),
    result_visibility     text     NOT NULL DEFAULT 'FULL' CHECK (result_visibility IN ('SCORE_ONLY', 'SCORE_AND_ANSWERS', 'FULL')),
    negative_mark         numeric(4,2) NOT NULL DEFAULT 0 CHECK (negative_mark BETWEEN 0 AND 1),
    pass_mark_pct         smallint CHECK (pass_mark_pct IS NULL OR pass_mark_pct BETWEEN 1 AND 100),
    allow_topic_choice    boolean  NOT NULL DEFAULT true,
    adaptive_enabled      boolean  NOT NULL DEFAULT false,
    max_retakes           integer  CHECK (max_retakes IS NULL OR max_retakes >= 0),
    version               integer  NOT NULL DEFAULT 0,
    PRIMARY KEY (exam_id, mode),
    CHECK (min_count >= 1 AND min_count <= default_count AND default_count <= max_count AND max_count <= 500),
    CHECK (mix_simple_pct + mix_medium_pct + mix_hard_pct = 100),
    CHECK (mode <> 'MOCK' OR time_limit_seconds IS NOT NULL)
);

CREATE TABLE catalog.misconception (                         -- FR-QB-11
    id           uuid PRIMARY KEY,
    topic_id     uuid REFERENCES catalog.topic (id),
    title        text        NOT NULL,
    description  text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE catalog.media_object (                          -- images live in object storage; this is the index
    key           text PRIMARY KEY,
    content_type  text        NOT NULL CHECK (content_type IN ('image/png', 'image/jpeg', 'image/webp')),
    size_bytes    integer     NOT NULL CHECK (size_bytes BETWEEN 1 AND 2097152),
    sha256        bytea       NOT NULL,
    uploaded_by   uuid        NOT NULL,
    uploaded_at   timestamptz NOT NULL DEFAULT now()
);

-- Question bank structure, created once per bank so the two never drift apart.
CREATE FUNCTION catalog.create_question_bank(bank text) RETURNS void LANGUAGE plpgsql AS $fn$
BEGIN
    EXECUTE format($ddl$
        CREATE SCHEMA %1$I;

        CREATE TABLE %1$I.question (
            id                  uuid PRIMARY KEY,
            topic_id            uuid        NOT NULL REFERENCES catalog.topic (id),
            status              text        NOT NULL DEFAULT 'DRAFT'
                                CHECK (status IN ('DRAFT', 'REVIEWED', 'PUBLISHED', 'ARCHIVED')),
            current_version_no  integer     NOT NULL DEFAULT 1,
            created_by          uuid        NOT NULL,
            updated_by          uuid        NOT NULL,
            created_at          timestamptz NOT NULL DEFAULT now(),
            updated_at          timestamptz NOT NULL DEFAULT now(),
            version             integer     NOT NULL DEFAULT 0
        );
        CREATE INDEX question_topic_status_idx ON %1$I.question (topic_id, status);

        -- Immutable snapshot; attempts reference version ids (FR-QB-05).
        CREATE TABLE %1$I.question_version (
            id                 uuid PRIMARY KEY,
            question_id        uuid        NOT NULL REFERENCES %1$I.question (id),
            version_no         integer     NOT NULL,
            answer_type        text        NOT NULL CHECK (answer_type IN ('SINGLE', 'MULTI')),
            stem_html          text        NOT NULL,          -- sanitised on write (allow-list)
            stem_text          text        NOT NULL,          -- plain text for search
            stem_media_key     text        REFERENCES catalog.media_object (key),
            stem_media_alt     text,
            explanation_html   text,
            definitions        jsonb       NOT NULL DEFAULT '[]'::jsonb,
            key_points         text[]      NOT NULL DEFAULT '{}',
            difficulty         text        NOT NULL CHECK (difficulty IN ('SIMPLE', 'MEDIUM', 'HARD')),
            est_seconds        integer     NOT NULL CHECK (est_seconds BETWEEN 10 AND 600),
            marks              numeric(5,2) NOT NULL DEFAULT 1 CHECK (marks > 0),
            frozen_at          timestamptz,                   -- set on publish; frozen versions never change
            created_by         uuid        NOT NULL,
            created_at         timestamptz NOT NULL DEFAULT now(),
            UNIQUE (question_id, version_no),
            CHECK (stem_media_key IS NULL OR stem_media_alt IS NOT NULL)
        );
        CREATE INDEX question_version_search_idx ON %1$I.question_version USING gin (to_tsvector('simple', stem_text));

        CREATE FUNCTION %1$I.guard_frozen_version() RETURNS trigger LANGUAGE plpgsql AS $g$
        BEGIN
            IF OLD.frozen_at IS NOT NULL THEN
                RAISE EXCEPTION 'question version %% is frozen (published) and cannot change', OLD.id;
            END IF;
            RETURN COALESCE(NEW, OLD);
        END $g$;
        CREATE TRIGGER question_version_frozen BEFORE UPDATE OR DELETE ON %1$I.question_version
            FOR EACH ROW EXECUTE FUNCTION %1$I.guard_frozen_version();

        CREATE FUNCTION %1$I.guard_frozen_option() RETURNS trigger LANGUAGE plpgsql AS $g$
        BEGIN
            IF EXISTS (SELECT 1 FROM %1$I.question_version v
                       WHERE v.id = COALESCE(OLD.version_id, NEW.version_id) AND v.frozen_at IS NOT NULL) THEN
                RAISE EXCEPTION 'options of a frozen (published) question version cannot change';
            END IF;
            RETURN COALESCE(NEW, OLD);
        END $g$;

        CREATE TABLE %1$I.question_option (
            id                uuid PRIMARY KEY,
            version_id        uuid    NOT NULL REFERENCES %1$I.question_version (id),
            label             char(1) NOT NULL CHECK (label IN ('A', 'B', 'C', 'D', 'E')),
            body_html         text    NOT NULL,
            media_key         text    REFERENCES catalog.media_object (key),
            media_alt         text,
            is_correct        boolean NOT NULL,
            misconception_id  uuid    REFERENCES catalog.misconception (id),
            UNIQUE (version_id, label),
            CHECK (media_key IS NULL OR media_alt IS NOT NULL),
            CHECK (NOT is_correct OR misconception_id IS NULL)
        );
        CREATE TRIGGER question_option_frozen BEFORE INSERT OR UPDATE OR DELETE ON %1$I.question_option
            FOR EACH ROW EXECUTE FUNCTION %1$I.guard_frozen_option();

        CREATE TABLE %1$I.question_tag (
            question_id uuid NOT NULL REFERENCES %1$I.question (id) ON DELETE CASCADE,
            tag         text NOT NULL CHECK (tag ~ '^[a-z0-9][a-z0-9 -]{0,40}$'),
            PRIMARY KEY (question_id, tag)
        );
    $ddl$, bank);
END $fn$;

SELECT catalog.create_question_bank('bank_trial');
SELECT catalog.create_question_bank('bank_main');

-- Read-only roles per bank: the free-access code path connects as catalog_trial_reader and cannot see bank_main.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'catalog_trial_reader') THEN
        CREATE ROLE catalog_trial_reader NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'catalog_main_reader') THEN
        CREATE ROLE catalog_main_reader NOLOGIN;
    END IF;
END $$;
GRANT USAGE ON SCHEMA catalog, bank_trial TO catalog_trial_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA catalog, bank_trial TO catalog_trial_reader;
GRANT USAGE ON SCHEMA catalog, bank_trial, bank_main TO catalog_main_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA catalog, bank_trial, bank_main TO catalog_main_reader;

CREATE TABLE catalog.audit_event (
    id              uuid PRIMARY KEY,
    actor_id        uuid,
    action          text        NOT NULL,
    target_type     text        NOT NULL,
    target_id       text,
    summary         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    correlation_id  text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_event_time_idx ON catalog.audit_event (created_at DESC);
CREATE TRIGGER audit_event_append_only BEFORE UPDATE OR DELETE ON catalog.audit_event
    FOR EACH ROW EXECUTE FUNCTION catalog.forbid_change();

CREATE TABLE catalog.outbox_event (
    seq            bigserial PRIMARY KEY,
    id             uuid        NOT NULL UNIQUE,
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    event_type     text        NOT NULL,            -- ExamPublished, ExamUpdated, ExamArchived
    payload        jsonb       NOT NULL,
    occurred_at    timestamptz NOT NULL DEFAULT now()
);
