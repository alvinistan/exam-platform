import { Link } from 'react-router';
import { ArrowRight, GraduationCap } from 'lucide-react';
import { Badge, Card } from '@/components/ui';
import { modeLabels, type ExamSummary } from './api';

interface ExamCardProps {
  exam: ExamSummary;
  categoryName?: string | undefined;
  headingLevel?: 'h2' | 'h3';
}

/**
 * Catalogue card. The whole card is one link (the title), so keyboard and screen-reader users meet a single,
 * well-named target. Cover images arrive with media uploads; until then every card shares the same calm header.
 */
export function ExamCard({ exam, categoryName, headingLevel = 'h3' }: ExamCardProps) {
  const Heading = headingLevel;
  return (
    <Card className="group relative flex h-full flex-col overflow-hidden transition-shadow hover:shadow-raised">
      <div className="flex h-24 items-end justify-between bg-[linear-gradient(120deg,var(--color-primary-soft),#f3f6ff_60%,var(--color-premium-soft))] px-5 pb-3">
        <span className="flex size-10 items-center justify-center rounded-md bg-surface text-primary shadow-card">
          <GraduationCap aria-hidden="true" className="size-5" />
        </span>
        {categoryName && <span className="text-[13px] font-semibold text-primary-ink">{categoryName}</span>}
      </div>
      <div className="flex flex-1 flex-col gap-3 p-5">
        <Heading className="text-lg leading-snug font-bold">
          <Link
            to={`/exams/${exam.slug}`}
            className="text-ink no-underline after:absolute after:inset-0 after:content-[''] hover:text-primary group-hover:text-primary"
          >
            {exam.title}
          </Link>
        </Heading>
        {exam.shortDescription && <p className="line-clamp-3 text-[14.5px] text-muted">{exam.shortDescription}</p>}
        <div className="mt-auto flex items-end justify-between gap-3 pt-1">
          {exam.modes.length > 0 ? (
            <ul className="flex flex-wrap gap-1.5" aria-label="Modes">
              {exam.modes.map((mode) => (
                <li key={mode}>
                  <Badge tone="neutral">{modeLabels[mode]}</Badge>
                </li>
              ))}
            </ul>
          ) : (
            <span />
          )}
          <ArrowRight aria-hidden="true" className="size-5 flex-none text-subtle transition-transform group-hover:translate-x-0.5 group-hover:text-primary" />
        </div>
      </div>
    </Card>
  );
}
