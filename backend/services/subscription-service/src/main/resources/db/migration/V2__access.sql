-- Phase 8: free access and entitlements.

-- FA-5: once a student has used an exam while it was free, it stays free for them even if a newly published exam
-- pushes it out of the free positions. Written when an attempt is started on a free exam.
CREATE TABLE free_exam_claim (
    student_id  uuid        NOT NULL,
    exam_id     uuid        NOT NULL,
    reason      text        NOT NULL,
    claimed_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (student_id, exam_id)
);

-- Why an administrator granted access by hand (shown in the entitlement list; also in the audit trail).
ALTER TABLE entitlement ADD COLUMN grant_reason text;
ALTER TABLE entitlement ADD CONSTRAINT entitlement_manual_reason CHECK (source = 'SUBSCRIPTION' OR grant_reason IS NOT NULL);

-- The catalogue feed carries the exam's title so administrators can read the free list without another lookup.
ALTER TABLE exam_view ADD COLUMN title text NOT NULL DEFAULT '';
ALTER TABLE exam_view ADD COLUMN slug text NOT NULL DEFAULT '';
