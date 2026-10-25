-- LOCAL DEVELOPMENT ONLY. Never run against staging or production.
--
-- Adds clearly labelled placeholder questions to one topic so an exam can be published and browsed locally before
-- question authoring exists (it arrives in the next phase). Each question is a complete, published, frozen version
-- with four options, so later phases can select it like any other question.
--
-- Usage (from the repository root, with the stack running):
--   docker compose -f infra/compose/docker-compose.yml --env-file .env exec -T postgres \
--     psql -U postgres -d catalog -v topic_title="Dosage by weight" -v how_many=10 < infra/dev-data/demo-questions.sql
--
-- topic_title must match exactly one active topic's title in the content library.

\set ON_ERROR_STOP on

-- psql variables aren't expanded inside the DO block, so pass them through session settings.
SELECT set_config('demo.topic_title', :'topic_title', false), set_config('demo.how_many', :'how_many', false);

DO $$
DECLARE
    v_topic   uuid;
    v_matches integer;
    v_q       uuid;
    v_version uuid;
    v_admin   uuid := '00000000-0000-0000-0000-00000000dead';   -- marks rows written by this script
    v_title   text := current_setting('demo.topic_title');
    v_count   integer := current_setting('demo.how_many')::integer;
    i         integer;
BEGIN
    SELECT count(*), min(id::text)::uuid INTO v_matches, v_topic
    FROM catalog.topic WHERE title = v_title AND status = 'ACTIVE';
    IF v_matches <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one active topic titled %, found %', v_title, v_matches;
    END IF;

    FOR i IN 1..v_count LOOP
        v_q := gen_random_uuid();
        v_version := gen_random_uuid();
        INSERT INTO bank_main.question (id, topic_id, status, created_by, updated_by)
        VALUES (v_q, v_topic, 'PUBLISHED', v_admin, v_admin);
        INSERT INTO bank_main.question_version (id, question_id, version_no, answer_type, stem_html, stem_text,
                                                 explanation_html, difficulty, est_seconds, created_by)
        VALUES (v_version, v_q, 1, 'SINGLE',
                '<p>[Demo] Placeholder question ' || i || ' for ' || v_title || '. Option A is correct.</p>',
                '[Demo] Placeholder question ' || i || ' for ' || v_title,
                '<p>This is demo content created by infra/dev-data/demo-questions.sql.</p>',
                (ARRAY['SIMPLE', 'MEDIUM', 'HARD'])[1 + (i % 3)], 60, v_admin);
        INSERT INTO bank_main.question_option (id, version_id, label, body_html, is_correct) VALUES
            (gen_random_uuid(), v_version, 'A', '<p>Demo answer A</p>', true),
            (gen_random_uuid(), v_version, 'B', '<p>Demo answer B</p>', false),
            (gen_random_uuid(), v_version, 'C', '<p>Demo answer C</p>', false),
            (gen_random_uuid(), v_version, 'D', '<p>Demo answer D</p>', false);
        UPDATE bank_main.question_version SET frozen_at = now() WHERE id = v_version;
    END LOOP;
    RAISE NOTICE 'Added % demo questions to topic %', v_count, v_title;
END $$;
