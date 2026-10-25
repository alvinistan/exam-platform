import type { AdminExam, SetupIssue, SetupStep } from '@/features/admin/catalog/api';

/** URL segment of each setup page: /admin/exams/:examId/:slug */
export type StepSlug = 'details' | 'subjects' | 'questions' | 'modes' | 'publish';

export interface StepDefinition {
  slug: StepSlug;
  number: number;
  title: string;
  hint: string;
  /** The server's name for this step's problems; publish has none of its own. */
  serverStep: SetupStep | null;
}

export const STEPS = [
  {
    slug: 'details',
    number: 1,
    title: 'Details',
    hint: 'Name, address, category',
    serverStep: 'DETAILS',
  },
  {
    slug: 'subjects',
    number: 2,
    title: 'Subjects & topics',
    hint: 'What the exam covers',
    serverStep: 'STRUCTURE',
  },
  {
    slug: 'questions',
    number: 3,
    title: 'Questions',
    hint: 'Write or import',
    serverStep: 'QUESTIONS',
  },
  {
    slug: 'modes',
    number: 4,
    title: 'Modes',
    hint: 'Practice, mock, guided',
    serverStep: 'MODES',
  },
  {
    slug: 'publish',
    number: 5,
    title: 'Review & publish',
    hint: 'Go live for students',
    serverStep: null,
  },
] as const satisfies readonly StepDefinition[];

export const stepBySlug = (slug: string | undefined) => STEPS.find((s) => s.slug === slug);

export const stepForIssue = (issue: SetupIssue): StepDefinition => STEPS.find((s) => s.serverStep === issue.step) ?? STEPS[0];

export type StepState = 'done' | 'todo' | 'problem';

export interface StepStatus {
  step: StepDefinition;
  state: StepState;
  /** One short line under the step's title, from real counts. */
  summary: string;
  issues: SetupIssue[];
}

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

/**
 * Where an exam is in its setup, worked out only from what the server reports. A step is done when nothing in it
 * still blocks publishing and it has what it needs; "problem" means the server reported something to fix there.
 */
export function setupStatus(exam: AdminExam): StepStatus[] {
  const issues = exam.publishReadiness.issues;
  const issuesFor = (step: StepDefinition) => issues.filter((i) => i.step === step.serverStep);
  return STEPS.map((step) => {
    const own = issuesFor(step);
    let done: boolean;
    let summary: string;
    switch (step.slug) {
      case 'details':
        done = own.length === 0;
        summary = own.length ? 'Needs attention' : 'Done';
        break;
      case 'subjects':
        done = exam.topicCount > 0 && own.length === 0;
        summary =
          exam.subjectCount === 0 ? step.hint : `${plural(exam.subjectCount, 'subject')} · ${plural(exam.topicCount, 'topic')}`;
        break;
      case 'questions':
        done = exam.publishedQuestionCount > 0 && own.length === 0;
        summary =
          exam.publishedQuestionCount + exam.draftQuestionCount === 0
            ? step.hint
            : `${exam.publishedQuestionCount} ready${exam.draftQuestionCount ? ` · ${exam.draftQuestionCount} draft` : ''}`;
        break;
      case 'modes':
        done = exam.modes.length > 0 && own.length === 0;
        summary = exam.modes.length ? `${exam.modes.length} of 3 on` : step.hint;
        break;
      case 'publish':
        done = exam.status === 'PUBLISHED';
        summary = exam.status === 'PUBLISHED' ? 'Live' : exam.publishReadiness.ready ? 'Ready to go live' : step.hint;
        break;
    }
    return {
      step,
      state: done ? 'done' : own.length ? 'problem' : 'todo',
      summary,
      issues: own,
    };
  });
}

/** The first step that isn't done: where "Continue setup" goes. */
export function nextStep(exam: AdminExam): StepStatus {
  const status = setupStatus(exam);
  const open = status.find((s) => s.state !== 'done');
  if (open) return open;
  const last = status[status.length - 1];
  if (!last) throw new Error('No setup steps');
  return last;
}

/** How many of the four building steps (1–4) are done, for progress bars. */
export function buildStepsDone(exam: AdminExam): number {
  return setupStatus(exam).filter((s) => s.step.slug !== 'publish' && s.state === 'done').length;
}

export const setupPath = (examId: string, slug: StepSlug) => `/admin/exams/${examId}/${slug}`;
