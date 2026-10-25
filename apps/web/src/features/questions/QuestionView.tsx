import { useId } from 'react';
import { Check, CircleCheck, CircleX, X } from 'lucide-react';
import { cn } from '@/lib/cn';

export interface ViewOption {
  label: string;
  bodyHtml: string;
  mediaUrl?: string | null;
  mediaAlt?: string | null;
  /** Only known once answers may be shown (review state). */
  isCorrect?: boolean;
}

export interface ViewQuestion {
  answerType: 'SINGLE' | 'MULTI';
  stemHtml: string;
  stemMediaUrl?: string | null;
  stemMediaAlt?: string | null;
  options: ViewOption[];
  explanationHtml?: string;
  keyPoints?: string[];
  definitions?: { term: string; meaning: string }[];
  difficulty?: string;
}

interface QuestionViewProps {
  question: ViewQuestion;
  /** "answering": options can be chosen; "review": the answer key, explanation and key points are shown. */
  mode: 'answering' | 'review';
  selected: string[];
  onSelect?: (labels: string[]) => void;
  number?: number;
}

/**
 * A question as a student sees it, shared by the admin preview (FR-QB-06) and, later, the attempt screens.
 * HTML here is server-sanitised question text (or editor output in the admin preview). Right and wrong are shown
 * with words and icons as well as colour.
 */
export function QuestionView({ question, mode, selected, onSelect, number }: QuestionViewProps) {
  const groupId = useId();
  const multi = question.answerType === 'MULTI';
  const review = mode === 'review';

  const choose = (label: string) => {
    if (review || !onSelect) return;
    if (multi) onSelect(selected.includes(label) ? selected.filter((l) => l !== label) : [...selected, label]);
    else onSelect([label]);
  };

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-col gap-3">
        {number !== undefined && <p className="text-sm font-semibold text-muted">Question {number}</p>}
        <div id={groupId} className="rich-text text-[17px] leading-relaxed text-ink" dangerouslySetInnerHTML={{ __html: question.stemHtml }} />
        {question.stemMediaUrl && (
          <img src={question.stemMediaUrl} alt={question.stemMediaAlt ?? ''} className="max-h-80 max-w-full self-start rounded-md border border-border" />
        )}
        <p className="text-sm text-muted">{multi ? 'Choose all answers that apply.' : 'Choose one answer.'}</p>
      </div>

      <div role={multi ? 'group' : 'radiogroup'} aria-labelledby={groupId} className="flex flex-col gap-2.5">
        {question.options.map((o) => {
          const isSelected = selected.includes(o.label);
          const right = review && o.isCorrect === true;
          const wrongPick = review && isSelected && o.isCorrect === false;
          return (
            <button
              key={o.label}
              type="button"
              role={multi ? 'checkbox' : 'radio'}
              aria-checked={isSelected}
              aria-disabled={review || undefined}
              onClick={() => choose(o.label)}
              className={cn(
                'flex w-full items-start gap-3 rounded-md border bg-surface px-4 py-3 text-left transition-colors',
                !review && 'hover:border-primary',
                !review && isSelected && 'border-primary bg-primary-soft',
                !review && !isSelected && 'border-border-strong',
                right && 'border-success bg-success-soft',
                wrongPick && 'border-danger bg-danger-soft',
                review && !right && !wrongPick && 'border-border',
              )}
            >
              <span
                aria-hidden="true"
                className={cn(
                  'flex size-7 flex-none items-center justify-center border text-sm font-bold',
                  multi ? 'rounded-sm' : 'rounded-full',
                  isSelected && !review ? 'border-primary bg-primary text-white' : 'border-border-strong text-ink-2',
                  right && 'border-success bg-success text-white',
                  wrongPick && 'border-danger bg-danger text-white',
                )}
              >
                {right ? <Check className="size-4" /> : wrongPick ? <X className="size-4" /> : o.label}
              </span>
              <span className="flex min-w-0 flex-1 flex-col gap-2">
                <span className="rich-text text-[15.5px] text-ink" dangerouslySetInnerHTML={{ __html: o.bodyHtml }} />
                {o.mediaUrl && <img src={o.mediaUrl} alt={o.mediaAlt ?? ''} className="max-h-48 max-w-full self-start rounded-sm" />}
                {review && (right || wrongPick || isSelected) && (
                  <span className={cn('flex items-center gap-1.5 text-[13px] font-semibold', right ? 'text-success-ink' : 'text-danger-ink')}>
                    {right ? <CircleCheck aria-hidden="true" className="size-4" /> : <CircleX aria-hidden="true" className="size-4" />}
                    {right && isSelected ? 'Correct — your answer' : right ? 'Correct answer' : 'Your answer — incorrect'}
                  </span>
                )}
              </span>
              <span className="sr-only">Option {o.label}</span>
            </button>
          );
        })}
      </div>

      {review && (question.explanationHtml || question.keyPoints?.length || question.definitions?.length) ? (
        <div className="flex flex-col gap-4 rounded-md border border-border bg-[#fafbfc] p-4">
          {question.explanationHtml && (
            <section className="flex flex-col gap-1.5">
              <h3 className="text-sm font-bold">Explanation</h3>
              <div className="rich-text text-[15px] text-ink-2" dangerouslySetInnerHTML={{ __html: question.explanationHtml }} />
            </section>
          )}
          {question.keyPoints && question.keyPoints.length > 0 && (
            <section className="flex flex-col gap-1.5">
              <h3 className="text-sm font-bold">Key points</h3>
              <ul className="list-disc pl-5 text-[15px] text-ink-2">
                {question.keyPoints.map((k) => (
                  <li key={k}>{k}</li>
                ))}
              </ul>
            </section>
          )}
          {question.definitions && question.definitions.length > 0 && (
            <section className="flex flex-col gap-1.5">
              <h3 className="text-sm font-bold">Definitions</h3>
              <dl className="flex flex-col gap-1 text-[15px]">
                {question.definitions.map((d) => (
                  <div key={d.term}>
                    <dt className="inline font-semibold">{d.term}: </dt>
                    <dd className="inline text-ink-2">{d.meaning}</dd>
                  </div>
                ))}
              </dl>
            </section>
          )}
        </div>
      ) : null}
    </div>
  );
}
