-- Phase 9: the exam flow for Practice, Mock and Adaptive exams.

-- What the student chose, and names copied from the catalogue at start so history and results read well even if
-- the exam or a topic is renamed or archived later.
ALTER TABLE attempt ADD COLUMN exam_title text NOT NULL DEFAULT '';
ALTER TABLE attempt ADD COLUMN exam_slug text NOT NULL DEFAULT '';
ALTER TABLE attempt ADD COLUMN topic_ids uuid[] NOT NULL DEFAULT '{}';     -- empty: every topic of the exam
ALTER TABLE attempt_question ADD COLUMN topic_title text NOT NULL DEFAULT '';

-- History lists and the "seen in the last 7 days" check read attempts per student and exam.
CREATE INDEX attempt_student_exam_idx ON attempt (student_id, exam_id, started_at DESC);

-- Every question gets a response row at start, so "unanswered" is explicit and answers are simple updates.
-- The question an answer belongs to never changes; responses are only ever updated in place.
