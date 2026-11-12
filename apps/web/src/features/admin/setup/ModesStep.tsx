import { Link } from 'react-router';
import { Card, buttonClasses } from '@/components/ui';
import { ModeSettingsForm } from '@/features/admin/catalog/ModeSettingsForm';
import type { AdminExamDetail } from '@/features/admin/catalog/api';
import { StepFooter, StepHeading } from './SetupShell';
import { setupPath } from './steps';

/** Step 4: which ways of studying the exam offers. Each mode saves on its own. */
export function ModesStep({ exam }: { exam: AdminExamDetail }) {
  return (
    <Card className="flex flex-col gap-6 p-6 sm:p-8">
      <StepHeading number={4} title="Exam types">
        Turn on at least one exam type. You have <strong className="text-ink">{exam.publishedQuestionCount} ready questions</strong> to
        share between them.
      </StepHeading>
      {exam.modeSettings.map((settings) => (
        <ModeSettingsForm
          key={settings.mode}
          examId={exam.id}
          settings={settings}
          publishedQuestions={exam.publishedQuestionCount}
        />
      ))}
      <StepFooter>
        <Link to={setupPath(exam.id, 'questions')} className="inline-flex min-h-11 items-center font-semibold">
          ← Back
        </Link>
        <Link to={setupPath(exam.id, 'publish')} className={buttonClasses('primary')}>
          Continue → Review &amp; publish
        </Link>
      </StepFooter>
    </Card>
  );
}
