-- Phase 9: free attempts per exam type, and a configurable feedback setting for the Mock exam.

-- How many attempts of this exam type a student may start without Premium (counted by subscription-service).
ALTER TABLE catalog.exam_mode_config
    ADD COLUMN free_attempts integer NOT NULL DEFAULT 3 CHECK (free_attempts BETWEEN 0 AND 1000);
UPDATE catalog.exam_mode_config SET free_attempts = 1 WHERE mode = 'MOCK';

-- Published exams are announced again so other services learn their free attempts straight away.
INSERT INTO catalog.outbox_event (id, aggregate_type, aggregate_id, event_type, payload)
SELECT gen_random_uuid(), 'Exam', e.id::text, 'ExamUpdated', jsonb_build_object(
           'examId', e.id, 'slug', e.slug, 'title', e.title, 'categoryId', e.category_id,
           'displayOrder', e.display_order, 'status', e.status, 'version', e.version,
           'modes', coalesce((SELECT jsonb_agg(m.mode ORDER BY array_position(ARRAY['PRACTICE', 'MOCK', 'USER_FRIENDLY'], m.mode))
                              FROM catalog.exam_mode_config m WHERE m.exam_id = e.id AND m.enabled), '[]'::jsonb),
           'freeAttempts', coalesce((SELECT jsonb_object_agg(m.mode, m.free_attempts)
                                     FROM catalog.exam_mode_config m WHERE m.exam_id = e.id AND m.enabled), '{}'::jsonb))
FROM catalog.exam e
WHERE e.status = 'PUBLISHED';
