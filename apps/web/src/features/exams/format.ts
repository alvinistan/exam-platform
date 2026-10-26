import type { PublicModeSettings } from './api';

/** "1 h 30 min", "45 min", "90 s" */
export function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds} s`;
  const minutes = Math.round(seconds / 60);
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) return `${m} min`;
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}

/** Plain-language facts about one exam type, for the exam page. */
export function modeFacts(s: PublicModeSettings): string[] {
  const facts: string[] = [];
  facts.push(`Up to ${s.maxCount} questions, or choose how many minutes you have`);
  if (s.mode === 'MOCK') {
    facts.push(s.timeLimitSeconds ? `Timed, up to ${formatDuration(s.timeLimitSeconds)}` : 'Timed');
  }
  facts.push(s.feedbackTiming === 'IMMEDIATE' ? 'See the answer after each question' : 'See answers after you submit');
  if (s.allowTopicChoice) facts.push('Choose the topics you want');
  if (s.passMarkPct) facts.push(`Pass mark ${s.passMarkPct}%`);
  if (s.negativeMark > 0) facts.push(`Wrong answers lose ${s.negativeMark} of a mark`);
  return facts;
}
