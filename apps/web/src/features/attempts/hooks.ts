import { useEffect, useRef, useState } from 'react';
import type { ViewQuestion } from '@/features/questions/QuestionView';
import type { Attempt, AttemptQuestion } from './api';

/** An attempt question in the shape the shared question component draws; correctness only once reviewed. */
export function toViewQuestion(q: AttemptQuestion): ViewQuestion {
  const review = q.review;
  return {
    answerType: q.answerType,
    stemHtml: q.stemHtml,
    stemMediaUrl: q.stemMediaUrl,
    stemMediaAlt: q.stemMediaAlt,
    options: q.options.map((o) => ({
      label: o.label,
      bodyHtml: o.bodyHtml,
      mediaUrl: o.mediaUrl,
      mediaAlt: o.mediaAlt,
      ...(review ? { isCorrect: review.correctLabels.includes(o.label) } : {}),
    })),
    ...(review?.explanationHtml ? { explanationHtml: review.explanationHtml } : {}),
    ...(review ? { keyPoints: review.keyPoints, definitions: review.definitions } : {}),
  };
}

/**
 * Seconds left on a timed attempt, following the server's clock: the offset between the server's time and this
 * device's is measured when the attempt is loaded, so a wrong device clock can't add or remove time (ADR-010).
 */
export function useSecondsLeft(attempt: Attempt): number | null {
  const { expiresAt } = attempt;
  const [offset] = useState(() => new Date(attempt.serverNow).getTime() - Date.now());
  const [left, setLeft] = useState(() => (expiresAt ? remaining(expiresAt, offset) : null));
  useEffect(() => {
    if (!expiresAt) return;
    const id = window.setInterval(() => setLeft(remaining(expiresAt, offset)), 500);
    return () => window.clearInterval(id);
  }, [expiresAt, offset]);
  return expiresAt ? left : null;
}

function remaining(expiresAt: string, offsetMs: number): number {
  return Math.max(0, (new Date(expiresAt).getTime() - (Date.now() + offsetMs)) / 1000);
}

/** Milliseconds spent on the current question since it was shown (or since the last save). */
export function useQuestionTimer(key: string | number) {
  const since = useRef(0);
  useEffect(() => {
    since.current = Date.now();
  }, [key]);
  return () => {
    const spent = Date.now() - since.current;
    since.current = Date.now();
    return Math.min(spent, 3_600_000);
  };
}
