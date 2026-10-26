-- Phase 9 Step B: adaptive question choice (ADR-016).

-- An Adaptive exam is given one question at a time, each chosen after the previous answer. planned_count is how many
-- it will have in total; the questions given so far are its attempt_question rows. Null for fixed question sets.
ALTER TABLE attempt ADD COLUMN planned_count integer CHECK (planned_count IS NULL OR planned_count >= 1);

-- The student's level on the question's topic when it was chosen (1 Easy, 2 Medium, 3 Hard): why it was given.
ALTER TABLE attempt_question ADD COLUMN target_level smallint CHECK (target_level IS NULL OR target_level BETWEEN 1 AND 3);

-- The performance profile reads a student's marked answers per exam, newest first.
CREATE INDEX response_answered_idx ON response (attempt_id, answered_at) WHERE is_correct IS NOT NULL;
