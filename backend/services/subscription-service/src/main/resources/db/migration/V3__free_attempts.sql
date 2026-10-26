-- Phase 9: free attempts per exam type replace "the first N exams in the catalogue are free".
--
-- Each exam sets, per mode, how many attempts a student may start without Premium (catalog-service, step 4 of the
-- exam setup). The catalogue feed carries those numbers; one row per counted attempt makes double counting impossible.

ALTER TABLE exam_view ADD COLUMN free_attempts jsonb NOT NULL DEFAULT '{}'::jsonb;   -- {"PRACTICE": 3, "MOCK": 1, ...}
ALTER TABLE exam_view ADD COLUMN modes text[] NOT NULL DEFAULT '{}';                  -- modes switched on

CREATE TABLE free_attempt_use (
    attempt_id  uuid PRIMARY KEY,
    student_id  uuid        NOT NULL,
    exam_id     uuid        NOT NULL,
    mode        text        NOT NULL CHECK (mode IN ('PRACTICE', 'MOCK', 'USER_FRIENDLY')),
    used_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX free_attempt_use_lookup_idx ON free_attempt_use (student_id, exam_id, mode);

-- The catalogue-position rules (FA-1, FA-2, FA-4, FA-5) are retired.
DROP TABLE free_exam_claim;
DROP TABLE allowance_usage;
DROP TABLE free_access_policy;
