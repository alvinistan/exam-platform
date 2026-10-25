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

/** Plain-language facts about one mode, for the exam page. */
export function modeFacts(s: PublicModeSettings): string[] {
  const facts: string[] = [];
  facts.push(
    s.minCount === s.maxCount
      ? `${s.defaultCount} questions`
      : `${s.defaultCount} questions by default (choose ${s.minCount}–${s.maxCount})`,
  );
  if (s.timeLimitSeconds) facts.push(`Time limit ${formatDuration(s.timeLimitSeconds)}`);
  else if (s.perQuestionSeconds) facts.push(`About ${formatDuration(s.perQuestionSeconds)} per question`);
  else facts.push('No time limit');
  facts.push(s.feedbackTiming === 'IMMEDIATE' ? 'See the answer after each question' : 'See answers after you submit');
  if (s.allowTopicChoice) facts.push('Choose the topics you want');
  if (s.passMarkPct) facts.push(`Pass mark ${s.passMarkPct}%`);
  if (s.negativeMark > 0) facts.push(`Wrong answers lose ${s.negativeMark} of a mark`);
  if (s.maxRetakes !== null && s.maxRetakes !== undefined) {
    facts.push(s.maxRetakes === 0 ? 'One attempt only' : `Up to ${s.maxRetakes} retakes`);
  }
  return facts;
}
